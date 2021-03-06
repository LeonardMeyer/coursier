package coursier.bootstrap.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import coursier.paths.CachePath;

class Download {

    private final static int concurrentDownloadCount;
    private final static File cacheDir;

    static {
        String prop = System.getProperty("coursier.parallel-download-count");
        if (prop == null)
            concurrentDownloadCount = 6;
        else
            concurrentDownloadCount = Integer.parseUnsignedInt(prop);
        try {
            cacheDir = CachePath.defaultCacheDirectory();
        } catch (IOException ex) {
            throw new RuntimeException("Error creating cache directory", ex);
        }
    }

    static List<URL> getLocalURLs(List<URL> urls) throws MalformedURLException {

        ThreadFactory threadFactory = new ThreadFactory() {
            AtomicInteger counter = new AtomicInteger(1);
            ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();
            public Thread newThread(Runnable r) {
                String name = "coursier-bootstrap-downloader-" + counter.getAndIncrement();
                Thread t = defaultThreadFactory.newThread(r);
                t.setName(name);
                t.setDaemon(true);
                return t;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(concurrentDownloadCount, threadFactory);

        try {
            return getLocalURLs(urls, pool);
        } finally {
            pool.shutdown();
        }
    }

    private static List<URL> getLocalURLs(List<URL> urls, ExecutorService pool) throws MalformedURLException {

        CompletionService<URL> completionService =
                new ExecutorCompletionService<>(pool);

        List<URL> localURLs = new ArrayList<>();
        List<URL> missingURLs = new ArrayList<>();

        for (URL url : urls) {

            String protocol = url.getProtocol();

            if (protocol.equals("file") || protocol.equals("jar")) {
                localURLs.add(url);
            } else {
                // fourth argument is false because we don't want to store local files when bootstrapping
                File dest = CachePath.localFile(url.toString(), cacheDir, null, false);

                if (dest.exists()) {
                    localURLs.add(dest.toURI().toURL());
                } else {
                    missingURLs.add(url);
                }
            }
        }

        for (final URL url : missingURLs) {
            completionService.submit(() -> {
                // fourth argument is false because we don't want to store local files when bootstrapping
                final File dest = CachePath.localFile(url.toString(), cacheDir, null, false);

                if (!dest.exists()) {
                    FileOutputStream out = null;
                    FileLock lock = null;

                    final File tmpDest = CachePath.temporaryFile(dest);
                    final File lockFile = CachePath.lockFile(tmpDest);

                    try {

                        out = CachePath.withStructureLock(cacheDir, () -> {
                            Files.createDirectories(tmpDest.toPath().getParent());
                            Files.createDirectories(lockFile.toPath().getParent());
                            Files.createDirectories(dest.toPath().getParent());

                            return new FileOutputStream(lockFile);
                        });

                        try {
                            lock = out.getChannel().tryLock();
                            if (lock == null)
                                throw new RuntimeException("Ongoing concurrent download for " + url);
                            else
                                try {
                                    URLConnection conn = url.openConnection();
                                    long lastModified = conn.getLastModified();
                                    int size = conn.getContentLength();
                                    InputStream s = conn.getInputStream();
                                    byte[] b = Util.readFullySync(s);
                                    // Seems java.net.HttpURLConnection doesn't always throw if the connection gets
                                    // abruptly closed during transfer, hence this extra check.
                                    if (size >= 0 && b.length != size) {
                                        throw new RuntimeException(
                                                "Error downloading " + url + " " +
                                                        "(expected " + size + " B, got " + b.length + " B), " +
                                                        "try again");
                                    }
                                    tmpDest.deleteOnExit();
                                    Util.writeBytesToFile(tmpDest, b);
                                    tmpDest.setLastModified(lastModified);
                                    Files.move(tmpDest.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
                                }
                                finally {
                                    lock.release();
                                    lock = null;
                                    out.close();
                                    out = null;
                                    lockFile.delete();
                                }
                        }
                        catch (OverlappingFileLockException e) {
                            throw new RuntimeException("Ongoing concurrent download for " + url);
                        }
                        finally {
                            if (lock != null) lock.release();
                        }
                    } catch (Exception e) {
                        System.err.println("Error while downloading " + url + ": " + e.getMessage() + ", ignoring it");
                        throw e;
                    }
                    finally {
                        if (out != null) out.close();
                    }
                }

                return dest.toURI().toURL();
            });
        }

        boolean useAnsiOutput = coursier.paths.Util.useAnsiOutput();
        String clearLine;
        String up;
        if (useAnsiOutput) {
            clearLine = "\033[2K";
            up = "\033[1A";
        } else {
            clearLine = "";
            up = "";
        }

        try {
            while (localURLs.size() < urls.size()) {
                Future<URL> future = completionService.take();
                try {
                    URL url = future.get();
                    localURLs.add(url);
                    int nowMissing = urls.size() - localURLs.size();
                    System.err.print(clearLine + "Downloaded " + (missingURLs.size() - nowMissing) + " missing file(s) / " + missingURLs.size() + "\n" + up);
                } catch (ExecutionException ex) {
                    // Error message already printed from the Callable above
                    System.exit(255);
                }
            }
        } catch (InterruptedException ex) {
            // ???
            System.err.println("Interrupted");
            System.exit(1);
        }

        if (!missingURLs.isEmpty()) {
            System.err.print(clearLine);
        }

        return localURLs;
    }

}
