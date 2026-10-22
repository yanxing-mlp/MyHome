package com.familyhome.file.biz.storage;

import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 存储目录的持久化身份登记。只管理根目录，不解释文件 key、不迁移或清理业务文件。
 *
 * <p>登记分两步提交：先保存路径、UUID 和 PENDING 状态，再以 CREATE_NEW 写两侧 marker。
 * 中断后只在原目录 fileKey/文件系统指纹仍匹配时补齐未写的 marker，绝不重建目录；READY
 * 行永远不补 marker。无 fileKey 的文件系统可首次登记，但中断后缺 marker 必须由运维恢复。
 * 新目录 POST 重试可以完成同一路径的 PENDING 登记，已完成的重复登记仍报错。
 */
@Component
@DependsOnDatabaseInitialization
public class StorageDirectoryRegistry {

    private static final Logger log = LoggerFactory.getLogger(StorageDirectoryRegistry.class);
    private static final String MARKER_FILE = ".myhome-storage-id";
    private static final boolean WINDOWS = java.io.File.separatorChar == '\\';
    private static final String SELECT = "SELECT id, path, marker, is_default, initialization_state, "
            + "public_identity, private_identity FROM file_storage_directory";
    private static final RowMapper<Directory> ROW_MAPPER = (rs, index) -> new Directory(
            rs.getLong("id"), rs.getString("path"), rs.getString("marker"), rs.getBoolean("is_default"),
            "READY".equals(rs.getString("initialization_state")),
            rs.getString("public_identity"), rs.getString("private_identity"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String configuredRoot;
    private final StorageDirectoryUsage directoryUsage;

    public StorageDirectoryRegistry(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                    @Value("${fh.storage.root}") String configuredRoot,
                                    StorageDirectoryUsage directoryUsage) {
        this.jdbc = jdbc;
        this.configuredRoot = configuredRoot;
        this.directoryUsage = directoryUsage;
        this.transaction = new TransactionTemplate(transactionManager);
        // marker 写入前必须确实提交 PENDING，不能被调用者的外层事务回滚成孤儿 marker。
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PostConstruct
    public void initialize() {
        List<Directory> existing = directories();
        if (existing.isEmpty()) {
            Path root;
            try {
                // 仅第一次兼容旧配置的相对路径；后续完全不再读取配置值。
                root = canonicalPath(parsePath(configuredRoot, false));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("首次存储目录配置无效，请检查 fh.storage.root", e);
            }
            Path legacyRoot = root;
            Boolean inserted = transaction.execute(status -> {
                if (!directories().isEmpty()) {
                    return false;
                }
                jdbc.update("INSERT INTO file_storage_directory (id, path, marker, is_default) VALUES (1, ?, ?, 1)",
                        legacyRoot.toString(), UUID.randomUUID().toString());
                return true;
            });
            if (Boolean.TRUE.equals(inserted)) {
                try {
                    // 只有本次刚插入 id1 的调用可以创建旧根；任何后续启动都不会执行这里。
                    prepareLegacyRoots(legacyRoot);
                    completeRegistration(1L, true);
                } catch (IOException | RuntimeException e) {
                    log.warn("历史存储目录登记尚未完成，保留 id=1 和已保存路径：{}", safeReason(e));
                }
            }
        }
        // READY 目录掉线不能影响应用启动。PENDING 恢复也逐行隔离失败。
        for (Directory directory : directories()) {
            if (!directory.ready()) {
                try {
                    completeRegistration(directory.id(), false);
                } catch (IOException | RuntimeException e) {
                    log.warn("存储目录 {} 初始化待恢复：{}", directory.id(), safeReason(e));
                }
            }
        }
    }

    /** 仅返回数据库中的历史根，不探测磁盘，以免旧盘掉线阻断应用启动。 */
    public Path legacyPath() {
        try {
            return Path.of(directory(1L).path());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("历史存储目录尚未登记");
        }
    }

    /** 历史读取只检查访问侧，另一侧掉线、只读或空间不足均不妨碍本侧读取。 */
    public Path pathFor(long id, boolean privateFile) {
        try {
            return readableSide(directory(id), privateFile).path();
        } catch (IOException | RuntimeException e) {
            // 不保留携带文件系统路径的原始异常或 cause。
            throw new IllegalArgumentException("存储目录不可用：" + safeReason(e));
        }
    }

    public long defaultIdForUpload() {
        try {
            List<Directory> defaults = jdbc.query(SELECT + " WHERE is_default = 1", ROW_MAPPER);
            if (defaults.size() != 1) {
                throw problem("没有唯一的默认存储目录，请联系管理员");
            }
            Directory directory = defaults.getFirst();
            writableDirectory(directory, true);
            return directory.id();
        } catch (IOException | RuntimeException e) {
            throw badRequest("默认存储目录不能上传：" + safeReason(e));
        }
    }

    public Snapshot listForAdmin() {
        CurrentUserHolder.requirePermission(PermissionCatalog.STORAGE_ACCESS);
        // 同一条 SELECT 拿默认及列表，不会跨越默认切换事务得到混合快照。
        List<Directory> rows = directories();
        long defaultId = rows.stream().filter(Directory::isDefault).mapToLong(Directory::id).findFirst().orElse(0L);
        List<DirectoryStatus> result = new ArrayList<>();
        for (Directory row : rows) {
            result.add(statusFor(row));
        }
        return new Snapshot(defaultId, Instant.now().toString(), List.copyOf(result));
    }

    /** 服务器目录选择器：仅浏览本层目录和只读预校验，不创建目录、探针或触发用量扫描。 */
    public FolderListing browseForAdmin(String path) {
        CurrentUserHolder.requirePermission(PermissionCatalog.STORAGE_ACCESS);
        try {
            Path requested = path == null ? null : parsePath(path, true, true);
            List<Directory> rows = directories();
            // 包含 PENDING 两侧。元数据无效时整体拒绝，不能把私人目录误当成可浏览目录。
            List<Path> protectedRoots = new ArrayList<>();
            for (Directory row : rows) {
                Path root = parsePath(row.path(), true);
                protectedRoots.add(root);
                protectedRoots.add(privateRoot(root));
            }
            List<FolderEntry> entries = new ArrayList<>();
            if (requested == null) {
                for (Path root : FileSystems.getDefault().getRootDirectories()) {
                    entries.add(folderEntry(root, protectedRoots));
                }
                return new FolderListing(null, null, false, null, sortedFolders(entries));
            }
            // 必须在探测/打开目录之前拒绝已登记根及后代，不暴露私人目录中的名字。
            requireOutsideRegisteredRoots(requested, protectedRoots);
            Path root = browsablePath(requested);
            requireOutsideRegisteredRoots(root, protectedRoots);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                for (Path child : children) {
                    // NOFOLLOW：不返回文件或符号链接；子项只检查可进入性，不检查空目录/私人侧。
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(child)) {
                        entries.add(folderEntry(child, protectedRoots));
                    }
                }
            }
            String reason = selectionDisabledReason(root, rows);
            return new FolderListing(root.toString(), root.getParent() == null ? null : root.getParent().toString(),
                    reason == null, reason, sortedFolders(entries));
        } catch (IOException | RuntimeException e) {
            throw badRequest("无法浏览目录：" + safeReason(e));
        }
    }

    private static List<FolderEntry> sortedFolders(List<FolderEntry> entries) {
        return entries.stream().sorted(java.util.Comparator.comparing(FolderEntry::name,
                String.CASE_INSENSITIVE_ORDER).thenComparing(FolderEntry::path)).toList();
    }

    private static FolderEntry folderEntry(Path path, List<Path> protectedRoots) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        try {
            requireOutsideRegisteredRoots(path, protectedRoots);
            Path real = browsablePath(parsePath(path.toString(), true, true));
            requireOutsideRegisteredRoots(real, protectedRoots);
            return new FolderEntry(name, real.toString(), true, null);
        } catch (IOException | RuntimeException e) {
            return new FolderEntry(name, path.toString(), false, safeReason(e));
        }
    }

    private static void requireOutsideRegisteredRoots(Path path, List<Path> protectedRoots) {
        for (Path root : protectedRoots) {
            if (nested(path, root)) {
                throw problem("已登记的公共或私人存储目录不能浏览");
            }
        }
    }

    private static Path browsablePath(Path path) throws IOException {
        Path real = canonicalPath(path);
        if (!samePath(real, path)) {
            throw problem("目录真实位置发生变化或使用了非规范别名，请重新选择");
        }
        requireReadableRoot(real);
        requireSearchPermission(real);
        return real;
    }

    private static void requireSearchPermission(Path path) {
        if (!WINDOWS && !Files.isExecutable(path)) {
            throw problem("没有进入目录的系统权限");
        }
    }

    private static String selectionDisabledReason(Path root, List<Directory> rows) {
        try {
            privateRoot(root); // 根可进入，但不可登记。
            ensureNoOverlap(root, rows);
            previewNewRoot(root, true);
            previewNewRoot(privateRoot(root), false);
            return null;
        } catch (IOException | RuntimeException e) {
            return safeReason(e);
        }
    }

    private static void previewNewRoot(Path root, boolean mustExist) throws IOException {
        Path real = canonicalPath(root);
        if (!samePath(real, root)) {
            throw problem("目录真实位置发生变化或使用了非规范别名，请重新选择");
        }
        // 与 POST 共用挂载位置和只读磁盘规则；不执行 createDirectories/probe。
        FileStore store = rootCreationStore(root);
        if (mustExist || Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            requireReadableRoot(root);
            requireSearchPermission(root);
            if (!Files.isWritable(root)) {
                throw problem("公共或私人目录没有写入权限");
            }
            requireEmpty(root, null);
        } else {
            Path parent = root.getParent();
            requireReadableRoot(parent);
            requireSearchPermission(parent);
            if (!Files.isWritable(parent)) {
                throw problem("没有创建配套私人目录的系统权限");
            }
        }
        if (store.getUsableSpace() <= 0) {
            throw problem("公共或私人目录可用空间不足");
        }
    }

    public long addForAdmin(String path) {
        CurrentUserHolder.requirePermission(PermissionCatalog.STORAGE_ACCESS);
        try {
            Path requested = parsePath(path, true);
            Registration registration = transaction.execute(status -> {
                lockRegistry();
                try {
                    Path root = canonicalPath(requested);
                    List<Directory> rows = directories();
                    for (Directory row : rows) {
                        if (samePath(root, Path.of(row.path()))) {
                            if (!row.ready()) {
                                // 重试不是首次登记，不能在缺少目录指纹时补写 marker。
                                return new Registration(row.id(), false);
                            }
                            throw problem("该存储目录已经登记");
                        }
                    }
                    ensureNoOverlap(root, rows);
                    prepareNewRoot(root);
                    prepareNewRoot(privateRoot(root));
                    // 创建后再做真实路径及重叠检查，防止已有路径别名绕过。
                    Path real = canonicalPath(root);
                    if (!samePath(real, root) || !samePath(canonicalPath(privateRoot(root)), privateRoot(root))) {
                        throw problem("目录真实位置发生变化，请重新选择");
                    }
                    ensureNoOverlap(real, rows);
                    probe(root);
                    probe(privateRoot(root));
                    requireEmpty(root, null);
                    requireEmpty(privateRoot(root), null);
                    return new Registration(insertPending(root, identity(root), identity(privateRoot(root))), true);
                } catch (IOException e) {
                    throw badRequest("无法登记目录：" + e.getMessage());
                }
            });
            Registration registered = Objects.requireNonNull(registration);
            completeRegistration(registered.id(), registered.fresh());
            return registered.id();
        } catch (IOException | StorageProblem | InvalidPathException e) {
            throw badRequest("无法登记目录：" + e.getMessage());
        } catch (DataAccessException e) {
            throw badRequest("目录登记失败或与已有目录重复，请检查数据库状态后重试");
        } catch (SecurityException e) {
            throw badRequest("没有访问该目录的系统权限");
        }
    }

    public void setDefaultForAdmin(long id) {
        CurrentUserHolder.requirePermission(PermissionCatalog.STORAGE_ACCESS);
        try {
            transaction.executeWithoutResult(status -> {
                lockRegistry();
                Directory target = directory(id);
                try {
                    writableDirectory(target, true);
                } catch (IOException e) {
                    throw badRequest("目标存储目录不能上传：" + e.getMessage());
                }
                if (!target.isDefault()) {
                    // 两条 UPDATE 在同一事务内；唯一生成列限制最多一个默认。
                    jdbc.update("UPDATE file_storage_directory SET is_default = 0 WHERE is_default = 1");
                    jdbc.update("UPDATE file_storage_directory SET is_default = 1 WHERE id = ?", id);
                }
            });
        } catch (StorageProblem e) {
            throw badRequest("目标存储目录不能上传：" + e.getMessage());
        } catch (DataAccessException e) {
            throw badRequest("切换默认目录失败，原默认目录未改变，请重试");
        } catch (SecurityException e) {
            throw badRequest("没有访问目标目录的系统权限，原默认目录未改变");
        }
    }

    public record Snapshot(long defaultDirectoryId, String refreshedAt, List<DirectoryStatus> directories) {
    }

    public record FolderListing(String path, String parentPath, boolean selectable, String disabledReason,
                                List<FolderEntry> directories) {
    }

    public record FolderEntry(String name, String path, boolean enterable, String disabledReason) {
    }

    public record DirectoryStatus(long id, String path, String privatePath,
                                  @JsonProperty("isDefault") boolean isDefault,
                                  boolean available, boolean writable, String error, String volume,
                                  Long totalBytes, Long usedBytes, Long usableBytes,
                                  Long directoryBytes, String directoryScannedAt,
                                  boolean directoryScanning, String directoryError) {
    }

    private record Directory(long id, String path, String marker, boolean isDefault, boolean ready,
                             String publicIdentity, String privateIdentity) {
    }

    private record Side(Path path, FileStore store) {
    }

    private record Registration(long id, boolean fresh) {
    }

    private List<Directory> directories() {
        return jdbc.query(SELECT + " ORDER BY id", ROW_MAPPER);
    }

    private Directory directory(long id) {
        List<Directory> rows = jdbc.query(SELECT + " WHERE id = ?", ROW_MAPPER, id);
        if (rows.size() != 1) {
            throw problem("存储目录未登记");
        }
        return rows.getFirst();
    }

    private void lockRegistry() {
        List<Long> rows = jdbc.query("SELECT id FROM file_storage_directory WHERE id = 1 FOR UPDATE",
                (rs, index) -> rs.getLong(1));
        if (rows.isEmpty()) {
            throw problem("历史存储目录尚未登记");
        }
    }

    private long insertPending(Path root, String publicIdentity, String privateIdentity) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO file_storage_directory "
                    + "(path, marker, public_identity, private_identity) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, root.toString());
            statement.setString(2, UUID.randomUUID().toString());
            statement.setString(3, publicIdentity);
            statement.setString(4, privateIdentity);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private void prepareLegacyRoots(Path root) throws IOException {
        createRoot(root);
        createRoot(privateRoot(root));
        String publicIdentity = identity(root);
        String privateIdentity = identity(privateRoot(root));
        transaction.executeWithoutResult(status -> {
            lockRegistry();
            jdbc.update("UPDATE file_storage_directory SET public_identity = ?, private_identity = ? "
                            + "WHERE id = 1 AND initialization_state = 'PENDING'",
                    publicIdentity, privateIdentity);
        });
    }

    private void completeRegistration(long id, boolean fresh) throws IOException {
        // checked IOException 通过私有包装跨出事务回调，确保任何失败都会回滚 READY 更新。
        try {
            transaction.executeWithoutResult(status -> {
                lockRegistry();
                Directory row = directory(id);
                if (row.ready()) {
                    return;
                }
                try {
                    installMarker(row, false, fresh);
                    installMarker(row, true, fresh);
                    readableSide(row, false);
                    readableSide(row, true);
                    jdbc.update("UPDATE file_storage_directory SET initialization_state = 'READY' WHERE id = ?", id);
                } catch (IOException e) {
                    throw new RegistrationIoException(e);
                }
            });
        } catch (RegistrationIoException e) {
            throw (IOException) e.getCause();
        }
    }

    private void installMarker(Directory row, boolean privateFile, boolean fresh) throws IOException {
        Path root = sidePath(row, privateFile);
        requireReadableRoot(root);
        Path marker = root.resolve(MARKER_FILE);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            verifyMarker(root, row.marker()); // 陌生/损坏/软链接 marker 永远不重写。
            if (row.id() != 1L) {
                requireEmpty(root, row.marker());
            }
            return;
        }
        String expectedIdentity = privateFile ? row.privateIdentity() : row.publicIdentity();
        String actualIdentity = identity(root);
        if (expectedIdentity == null ? !fresh : !expectedIdentity.equals(actualIdentity)) {
            throw problem("登记未完成且无法确认原磁盘身份；请恢复原目录及身份标记后重试");
        }
        if (row.id() != 1L) {
            requireEmpty(root, null);
        }
        try {
            Files.writeString(marker, row.marker(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException e) {
            verifyMarker(root, row.marker());
        }
        verifyMarker(root, row.marker());
    }

    private DirectoryStatus statusFor(Directory row) {
        String privatePath;
        try {
            privatePath = sidePath(row, true).toString();
        } catch (RuntimeException e) {
            directoryUsage.invalidate(row.id());
            return new DirectoryStatus(row.id(), row.path(), "", row.isDefault(), false, false,
                    "已登记路径格式无效", null, null, null, null, null, null, false, null);
        }
        try {
            Side publicSide = readableSide(row, false);
            Side privateSide = readableSide(row, true);
            // 只展示公共根所在文件系统一次，不递归计算目录，也不将两侧容量相加。
            FileStore store = publicSide.store();
            long total = store.getTotalSpace();
            long used = total - store.getUnallocatedSpace();
            long usable = store.getUsableSpace();
            boolean writable = canWrite(publicSide) && canWrite(privateSide);
            String error = !row.ready() ? "目录登记尚未完成" : !writable ? "公共或私人目录只读或不可写"
                    : usable <= 0 || privateSide.store().getUsableSpace() <= 0 ? "公共或私人目录可用空间不足" : null;
            StorageDirectoryUsage.Snapshot usage;
            if (row.ready()) {
                usage = directoryUsage.snapshot(row.id(), publicSide.path(), privateSide.path(), () -> {
                    // 使用本次 SELECT 的不可变登记信息，不在线程池中访问 CurrentUser 或数据库。
                    readableSide(row, false);
                    readableSide(row, true);
                });
            } else {
                directoryUsage.invalidate(row.id());
                usage = new StorageDirectoryUsage.Snapshot(null, null, false, null);
            }
            return new DirectoryStatus(row.id(), row.path(), privatePath, row.isDefault(), true,
                    writable && row.ready(), error, store.name() + " (" + store.type() + ")", total, used, usable,
                    usage.bytes(), usage.scannedAt(), usage.scanning(), usage.error());
        } catch (IOException | RuntimeException e) {
            directoryUsage.invalidate(row.id());
            return new DirectoryStatus(row.id(), row.path(), privatePath, row.isDefault(), false, false,
                    safeReason(e), null, null, null, null, null, null, false, null);
        }
    }

    private Side readableSide(Directory row, boolean privateFile) throws IOException {
        try {
            Path root = sidePath(row, privateFile);
            requireReadableRoot(root);
            verifyMarker(root, row.marker());
            return new Side(root, Files.getFileStore(root));
        } catch (StorageProblem e) {
            throw problem((privateFile ? "私人目录：" : "公共目录：") + e.getMessage());
        } catch (IOException e) {
            throw problem((privateFile ? "私人目录" : "公共目录") + "离线或无法读取，请检查磁盘及权限");
        }
    }

    private void writableDirectory(Directory row, boolean writeProbe) throws IOException {
        if (!row.ready()) {
            throw problem("目录登记尚未完成，请管理员恢复磁盘身份标记");
        }
        for (boolean privateFile : new boolean[]{false, true}) {
            Side side = readableSide(row, privateFile);
            String name = privateFile ? "私人目录" : "公共目录";
            if (!canWrite(side)) {
                throw problem(name + "只读或没有写入权限");
            }
            if (side.store().getUsableSpace() <= 0) {
                throw problem(name + "可用空间不足");
            }
            if (writeProbe) {
                try {
                    probe(side.path());
                } catch (IOException e) {
                    throw problem(name + "写入探针失败，请检查磁盘空间及权限");
                }
                readableSide(row, privateFile);
            }
        }
    }

    private static boolean canWrite(Side side) throws IOException {
        return !side.store().isReadOnly() && Files.isWritable(side.path());
    }

    private static Path sidePath(Directory row, boolean privateFile) {
        Path root = Path.of(row.path());
        return privateFile ? privateRoot(root) : root;
    }

    private static Path privateRoot(Path root) {
        if (root.getFileName() == null || root.getParent() == null) {
            throw problem("不能使用文件系统根目录");
        }
        return root.resolveSibling(root.getFileName() + "-private");
    }

    private static Path parsePath(String input, boolean strict) {
        return parsePath(input, strict, false);
    }

    private static Path parsePath(String input, boolean strict, boolean allowRoot) {
        if (input == null || input.isBlank() || input.length() > 1024 || input.indexOf('\0') >= 0) {
            throw problem("请输入不超过 1024 字符的有效目录路径");
        }
        Path path = Path.of(input);
        if (strict && (!path.isAbsolute() || !path.equals(path.normalize())
                || !(WINDOWS ? input.replace('/', '\\') : input).equals(path.toString()))) {
            throw problem("必须使用规范的绝对路径，不允许相对路径、重复分隔符或 . / ..");
        }
        path = path.toAbsolutePath().normalize();
        if (!allowRoot) {
            privateRoot(path); // 默认仍拒绝文件系统根、Windows 盘符根与 UNC 共享根。
        }
        if (WINDOWS) {
            for (Path part : path) {
                String name = part.toString();
                String device = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
                if (name.endsWith(".") || name.endsWith(" ") || name.indexOf(':') >= 0
                        || device.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) {
                    throw problem("Windows 路径不能使用设备名、数据流或尾随空格/点");
                }
            }
        }
        return path;
    }

    /** 即使末级尚不存在，也用最近的真实祖先生成唯一规范路径。 */
    private static Path canonicalPath(Path path) throws IOException {
        noSymlinkAncestors(path);
        Path ancestor = existingAncestor(path);
        Path canonical = ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
        if (WINDOWS) {
            canonical = Path.of(canonical.toString().toLowerCase(Locale.ROOT));
        }
        if (canonical.toString().length() > 1024) {
            throw problem("规范化后的目录路径超过 1024 字符");
        }
        return canonical;
    }

    private static Path existingAncestor(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()) {
                    throw problem("路径或其祖先不是目录");
                }
                return current;
            } catch (NoSuchFileException e) {
                // 只跳过确实不存在的目录；权限/IO 错误不能当成不存在。
            }
        }
        throw problem("父磁盘或网络共享不存在，请先挂载");
    }

    private static void noSymlinkAncestors(Path path) throws IOException {
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw problem("存储路径不是规范绝对路径");
        }
        for (Path current = path; current != null; current = current.getParent()) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw problem("存储路径及祖先不能包含符号链接或重解析点");
                }
                if (!attributes.isDirectory()) {
                    throw problem("路径或其祖先不是目录");
                }
                // Windows junction 不一定被 provider 标为 symbolicLink，真实位置也必须一致。
                if (WINDOWS && !samePath(current, current.toRealPath())) {
                    throw problem("Windows 路径包含重解析点或非规范目录别名");
                }
            } catch (NoSuchFileException e) {
                // 允许尚未创建的新目录，但仍继续检查更高的祖先。
            }
        }
    }

    private static void requireReadableRoot(Path root) throws IOException {
        noSymlinkAncestors(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw problem("目录不存在或磁盘离线");
        }
        if (!Files.isReadable(root)) {
            throw problem("目录不可读，请检查系统权限");
        }
    }

    private static void createRoot(Path root) throws IOException {
        rootCreationStore(root);
        Files.createDirectories(root);
        requireReadableRoot(root);
    }

    /** 登记与浏览预校验共用的只读路径、挂载位置及磁盘检查。 */
    private static FileStore rootCreationStore(Path root) throws IOException {
        noSymlinkAncestors(root);
        Path ancestor = existingAncestor(root);
        // 不从文件系统根凭空制造磁盘目录；嵌套新目录必须有已经存在的父存储目录。
        if (!WINDOWS && ancestor.equals(root.getRoot()) && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw problem("父存储目录不存在，请先创建或挂载父磁盘");
        }
        FileStore store = Files.getFileStore(ancestor);
        if (!WINDOWS) {
            // 常见可移动盘挂载容器不是磁盘。卸载后留下的空挂载点不能被当成系统盘使用。
            for (String location : List.of("/mnt", "/media", "/run/media", "/Volumes")) {
                Path container = Path.of(location);
                if (root.startsWith(container)) {
                    Path parent = existingAncestor(container.getParent());
                    if (store.equals(Files.getFileStore(parent))) {
                        throw problem("目标磁盘尚未挂载，不能在挂载容器中创建目录");
                    }
                }
            }
        }
        if (store.isReadOnly()) {
            throw problem("父磁盘为只读");
        }
        return store;
    }

    private static void prepareNewRoot(Path root) throws IOException {
        // 创建前先检查已有目录，绝不把非空资料目录关联到公开资源根。
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            requireReadableRoot(root);
            requireEmpty(root, null);
        }
        createRoot(root);
        requireEmpty(root, null);
    }

    private static void requireEmpty(Path root, String allowedMarker) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (allowedMarker != null && entry.getFileName().toString().equals(MARKER_FILE)) {
                    verifyMarker(root, allowedMarker);
                } else {
                    throw problem("新存储目录及其私人目录必须为空，不能关联已有资料目录");
                }
            }
        }
    }

    private static void ensureNoOverlap(Path root, List<Directory> rows) throws IOException {
        List<Path> proposed = List.of(root, privateRoot(root));
        for (Directory row : rows) {
            for (Path existing : List.of(Path.of(row.path()), privateRoot(Path.of(row.path())))) {
                for (Path candidate : proposed) {
                    if (nested(candidate, existing) || nested(existing, candidate)
                            || (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                            && Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(candidate, existing))) {
                        throw problem("公共/私人目录与已登记目录 #" + row.id() + " 相同或存在父子重叠");
                    }
                }
            }
        }
    }

    private static boolean nested(Path child, Path parent) {
        if (WINDOWS) {
            return Path.of(child.toString().toLowerCase(Locale.ROOT))
                    .startsWith(Path.of(parent.toString().toLowerCase(Locale.ROOT)));
        }
        return child.startsWith(parent);
    }

    private static boolean samePath(Path left, Path right) {
        return WINDOWS ? left.toString().equalsIgnoreCase(right.toString()) : left.equals(right);
    }

    private static void verifyMarker(Path root, String expected) throws IOException {
        Path marker = root.resolve(MARKER_FILE);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw problem("磁盘身份标记缺失，禁止访问或写入，请恢复原磁盘");
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() != 36) {
            throw problem("磁盘身份标记无效或不是普通文件");
        }
        ByteBuffer buffer = ByteBuffer.allocate(37);
        try (var channel = Files.newByteChannel(marker, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // 限量读取，不能将异常 marker 当成任意大小的文件载入内存。
            }
        }
        buffer.flip();
        if (!expected.equals(StandardCharsets.UTF_8.decode(buffer).toString())) {
            throw problem("磁盘身份不匹配，请接回原磁盘，不能覆盖身份标记");
        }
    }

    /** 只创建并删除本次随机探针，不枚举或清理旧文件。 */
    private static void probe(Path root) throws IOException {
        requireReadableRoot(root);
        Path probe = root.resolve(".myhome-storage-probe-" + UUID.randomUUID());
        boolean created = false;
        try {
            try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                channel.write(ByteBuffer.wrap(new byte[]{1}));
                channel.force(true);
            }
        } finally {
            if (created) {
                Files.delete(probe);
            }
        }
    }

    private static String identity(Path root) throws IOException {
        requireReadableRoot(root);
        Object fileKey = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (fileKey == null) {
            return null;
        }
        FileStore store = Files.getFileStore(root);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (store.name() + "\n" + store.type() + "\n" + fileKey).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 普通读/上传错误只允许固定文案，不把 IOException、SQL 或 InvalidPathException 文本透出。 */
    private static String safeReason(Exception e) {
        if (e instanceof StorageProblem) {
            return e.getMessage();
        }
        if (e instanceof DataAccessException) {
            return "存储目录元数据暂时无法读取";
        }
        if (e instanceof SecurityException) {
            return "没有访问存储目录的系统权限";
        }
        return "磁盘离线或无法访问，请联系管理员检查存储目录";
    }

    private static StorageProblem problem(String message) {
        return new StorageProblem(message);
    }

    private static BizException badRequest(String message) {
        return BizException.of(ErrorCode.BAD_REQUEST, message);
    }

    private static final class StorageProblem extends RuntimeException {
        private StorageProblem(String message) {
            super(message);
        }
    }

    private static final class RegistrationIoException extends RuntimeException {
        private RegistrationIoException(IOException cause) {
            super(cause);
        }
    }
}
