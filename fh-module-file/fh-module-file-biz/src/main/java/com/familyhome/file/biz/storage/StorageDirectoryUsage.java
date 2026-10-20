package com.familyhome.file.biz.storage;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.stereotype.Component;

/**
 * 登记 public/private 根内普通文件的逻辑字节数（含缩略图），不是磁盘块占用。
 * 请求只取缓存并排队；这是最终一致的统计，不是原子文件系统快照。
 * 不读取文件内容，不查询 file_object，也不创建、修改或删除任何文件。
 */
@Component
public class StorageDirectoryUsage {
    private static final long REFRESH_SECONDS = 60;
    private static final String SCAN_ERROR = "目录大小统计失败，请稍后重试";
    private static final Snapshot EMPTY = new Snapshot(null, null, false, null);

    private final ExecutorService executor;
    private final Clock clock;
    // 所有缓存操作使用同一短锁；锁内不访问文件系统、不等待 worker。
    private final Map<Long, Entry> entries = new HashMap<>();
    private volatile boolean closed;

    public StorageDirectoryUsage() {
        this(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "storage-directory-usage");
            thread.setDaemon(true);
            return thread;
        }), Clock.systemUTC());
    }

    StorageDirectoryUsage(ExecutorService executor, Clock clock) {
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
    }

    public record Snapshot(Long bytes, String scannedAt, boolean scanning, String error) {
    }

    @FunctionalInterface
    public interface RootValidation {
        void validate() throws IOException;
    }

    /** 仅供 Registry 传入已校验的、不可变的登记根；validation 不依赖请求线程身份。 */
    public synchronized Snapshot snapshot(long id, Path publicPath, Path privatePath, RootValidation validation) {
        if (closed) {
            return EMPTY;
        }
        Objects.requireNonNull(validation);
        Entry entry = entries.computeIfAbsent(id, ignored -> new Entry(publicPath, privatePath));
        if (!entry.publicPath.equals(publicPath) || !entry.privatePath.equals(privatePath)) {
            invalidate(id);
            // 同一登记 id 的路径是不可变元数据，不能借缓存接口重定向扫描。
            return new Snapshot(null, null, false, SCAN_ERROR);
        }
        if (!entry.scanning && (entry.finishedAt == null
                || !clock.instant().isBefore(entry.finishedAt.plusSeconds(REFRESH_SECONDS)))) {
            entry.scanning = true;
            Object token = entry.token;
            try {
                executor.execute(() -> scan(entry, token, validation));
            } catch (RejectedExecutionException e) {
                entry.scanning = false;
                entry.result = new Snapshot(null, null, false, SCAN_ERROR);
                entry.finishedAt = clock.instant();
            }
        }
        return new Snapshot(entry.result.bytes(), entry.result.scannedAt(), entry.scanning, entry.result.error());
    }

    public synchronized void invalidate(long id) {
        Entry entry = entries.get(id);
        if (entry != null) {
            entry.token = new Object();
            entry.result = EMPTY;
            entry.finishedAt = null;
            // 保留在途标志直到旧任务退出，避免恢复在线的请求为同一 id 再排一个任务。
        }
    }

    @PreDestroy
    public void close() {
        synchronized (this) {
            closed = true;
            entries.values().forEach(entry -> entry.token = new Object());
            entries.clear();
        }
        executor.shutdownNow();
    }

    private void scan(Entry entry, Object token, RootValidation validation) {
        Long bytes = null;
        try {
            checkActive(entry, token);
            validation.validate();
            Set<FileIdentity> counted = new HashSet<>();
            long publicBytes = scanRoot(entry.publicPath, counted, entry, token);
            long privateBytes = scanRoot(entry.privatePath, counted, entry, token);
            checkActive(entry, token);
            validation.validate();
            bytes = Math.addExact(publicBytes, privateBytes);
        } catch (IOException | RuntimeException e) {
            // 不透传路径、文件名、provider 或 Registry 异常；失败不能发布部分总量。
        } finally {
            synchronized (this) {
                entry.scanning = false;
                if (!closed && entry.token == token) {
                    Instant finished = clock.instant();
                    entry.finishedAt = finished;
                    entry.result = bytes == null ? new Snapshot(null, null, false, SCAN_ERROR)
                            : new Snapshot(bytes, finished.toString(), false, null);
                }
            }
        }
    }

    private long scanRoot(Path root, Set<FileIdentity> counted, Entry entry, Object token) throws IOException {
        checkActive(entry, token);
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!plainDirectory(attributes) || !realPathMatches(root)) {
            throw new IOException("Invalid root");
        }
        FileStore store = Files.getFileStore(root);
        Deque<Frame> stack = new ArrayDeque<>();
        long total = 0;
        try {
            stack.push(openDirectory(root, attributes, store, null));
            while (!stack.isEmpty()) {
                checkActive(entry, token);
                Frame frame = stack.peek();
                verifyDirectory(frame.path, frame.attributes, store);
                if (!frame.iterator.hasNext()) {
                    stack.pop().stream.close();
                    continue;
                }
                Path child = frame.iterator.next();
                String name = child.getFileName().toString();
                if (frame.path.equals(root) && (name.equals(".myhome-storage-id")
                        || name.startsWith(".myhome-storage-probe-"))) {
                    continue;
                }
                BasicFileAttributes childAttributes = readChild(frame, child);
                if (childAttributes.isSymbolicLink() || childAttributes.isOther()
                        || (!childAttributes.isDirectory() && !childAttributes.isRegularFile())) {
                    continue;
                }
                // junction 等 provider 未标为链接的别名也不能进入。挂载点在打开 stream 前排除。
                if (!realPathMatches(child) || !store.equals(Files.getFileStore(child))) {
                    continue;
                }
                if (childAttributes.isDirectory()) {
                    stack.push(openDirectory(child, childAttributes, store, frame));
                } else {
                    Object key = childAttributes.fileKey();
                    if (key == null || counted.add(new FileIdentity(child.getFileSystem(), store, key))) {
                        total = Math.addExact(total, childAttributes.size());
                    }
                }
            }
        } finally {
            IOException failure = null;
            while (!stack.isEmpty()) {
                try {
                    stack.pop().stream.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
        return total;
    }

    private static BasicFileAttributes readChild(Frame parent, Path child) throws IOException {
        if (parent.stream instanceof SecureDirectoryStream<Path> secure) {
            return secure.getFileAttributeView(child.getFileName(), BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
        }
        return Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static Frame openDirectory(Path path, BasicFileAttributes expected, FileStore store, Frame parent)
            throws IOException {
        // walkFileTree 的 preVisitDirectory 在 stream 打开之后才执行，不适合这里的边界校验。
        verifyDirectory(path, expected, store);
        DirectoryStream<Path> stream = parent != null && parent.stream instanceof SecureDirectoryStream<Path> secure
                ? secure.newDirectoryStream(path.getFileName(), LinkOption.NOFOLLOW_LINKS)
                : Files.newDirectoryStream(path);
        try {
            verifyDirectory(path, expected, store);
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                BasicFileAttributes opened = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
                if (!plainDirectory(opened) || !Objects.equals(expected.fileKey(), opened.fileKey())) {
                    throw new IOException("Directory changed");
                }
            }
            return new Frame(path, expected, stream, stream.iterator());
        } catch (IOException | RuntimeException | Error e) {
            try {
                stream.close();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    private static void verifyDirectory(Path path, BasicFileAttributes expected, FileStore store) throws IOException {
        BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!plainDirectory(current) || !Objects.equals(expected.fileKey(), current.fileKey())
                || !realPathMatches(path) || !store.equals(Files.getFileStore(path))) {
            throw new IOException("Directory changed");
        }
    }

    private static boolean plainDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }

    private static boolean realPathMatches(Path path) throws IOException {
        return path.isAbsolute() && path.equals(path.normalize()) && path.equals(path.toRealPath());
    }

    private void checkActive(Entry entry, Object token) throws InterruptedIOException {
        if (closed || entry.token != token || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Scan cancelled");
        }
    }

    private record Frame(Path path, BasicFileAttributes attributes, DirectoryStream<Path> stream,
                         Iterator<Path> iterator) {
    }

    private record FileIdentity(FileSystem fileSystem, FileStore store, Object key) {
    }

    private static final class Entry {
        final Path publicPath;
        final Path privatePath;
        volatile Object token = new Object();
        boolean scanning;
        Instant finishedAt;
        Snapshot result = EMPTY;

        Entry(Path publicPath, Path privatePath) {
            this.publicPath = Objects.requireNonNull(publicPath);
            this.privatePath = Objects.requireNonNull(privatePath);
        }
    }
}
