package com.vectorsearch.faiss.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.logging.Logger;

import com.globalload.LibraryLoaderJNI;

/**
 * A simple library class which helps with loading dynamic libraries stored in the JAR archive.
 * These libraries usually contain implementation of some methods in native code (using JNI - Java
 * Native Interface).
 *
 * @see <a href="http://adamheinrich.com/blog/2012/how-to-load-native-jni-library-from-jar">http://adamheinrich.com/blog/2012/how-to-load-native-jni-library-from-jar</a>
 * @see <a href="https://github.com/adamheinrich/native-utils">https://github.com/adamheinrich/native-utils</a>
 */
public class NativeUtils2 {
    public static final String NATIVE_FOLDER_PATH_PREFIX = "nativeutils2";
    private static final Logger LOGGER = Logger.getLogger(NativeUtils2.class.getName());
    /**
     * The minimum length a prefix for a file has to have according to {@link
     * File#createTempFile(String, String)}}.
     */
    private static final int MIN_PREFIX_LENGTH = 3;
    /**
     * Temporary directory which will contain the DLLs.
     */
    private static File temporaryDir;

    /**
     * Private constructor - this class will never be instanced
     */
    private NativeUtils2() {
    }

    /**
     * Loads library from current JAR archive
     * <p>
     * The file from JAR is copied into system temporary directory and then loaded. The temporary file
     * is deleted after exiting. Method uses String as filename because the pathname is "abstract",
     * not system-dependent.
     *
     * @param path The path of file inside JAR as absolute path (beginning with '/'), e.g.
     *             /package/File.ext
     * @param requiredLibPaths The list of additional paths to add in the temporary file in order to load
     *                         the library.
     * @throws IOException              If temporary file creation or read/write operation fails
     * @throws IllegalArgumentException If source file (param path) does not exist
     * @throws IllegalArgumentException If the path is not absolute or if the filename is shorter than
     *                                  three characters (restriction of {@link File#createTempFile(String,
     *                                  String)}).
     * @throws FileNotFoundException    If the file could not be found inside the JAR.
     */
    public static void loadLibraryFromJar(String path, String[] requiredLibPaths) throws IOException {

        /* Prepare temporary file */
        if (temporaryDir == null) {
            temporaryDir = createTempDirectory();
            temporaryDir.deleteOnExit();
        }

        /* Copy all the .so lib required */
        copyFileToDir(path, temporaryDir);
        for (String libPath : requiredLibPaths) {
            copyFileToDir(libPath, temporaryDir);
        }

        /* Load the required libraries globally (RTLD_GLOBAL | RTLD_LAZY) in the given order */
        for (String libPath : requiredLibPaths) {
            File tempLibToLoadBefore = new File(temporaryDir, baseName(libPath));
            try{
                LibraryLoaderJNI.loadLibrary(tempLibToLoadBefore.getAbsolutePath());
            } catch (UnsatisfiedLinkError e){
                LOGGER.warning(e.getMessage());
            }

        }

        /* Load the native library locally */
        final File tempLibToLoad = new File(temporaryDir, baseName(path));
        try {
            System.load(tempLibToLoad.getAbsolutePath());
        } finally {
            if (isPosixCompliant()) {
                /* Assume POSIX compliant file system, can be deleted after loading */
                final boolean status = tempLibToLoad.delete();
                if (status) {
                    LOGGER.info("Deleted file : " + tempLibToLoad.getAbsolutePath());
                }
            } else {
                /* Assume non-POSIX, and don't delete until last file descriptor closed */
                tempLibToLoad.deleteOnExit();
            }
        }
    }

    private static void copyFileToDir(String srcPath, File dstDirPath) throws IOException {

        if (srcPath == null || !srcPath.startsWith("/")) {
            throw new IllegalArgumentException("The path has to be absolute (start with '/').");
        }

        /* Obtain filename from path */
        String filename = baseName(srcPath);

        /* Check if the filename is okay */
        if (filename == null || filename.length() < MIN_PREFIX_LENGTH) {
            throw new IllegalArgumentException("The filename has to be at least 3 characters long.");
        }

        File dstTemp = new File(dstDirPath, filename);

        try (final InputStream is = NativeUtils2.class.getResourceAsStream(srcPath)) {
            Files.copy(is, dstTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            final boolean status = dstTemp.delete();
            if (status) {
                LOGGER.info("Deleted file : " + dstTemp.getAbsolutePath());
            }
            throw e;
        } catch (NullPointerException e) {
            final boolean status = dstTemp.delete();
            if (status) {
                LOGGER.info("Deleted file : " + dstTemp.getAbsolutePath());
            }
            throw new FileNotFoundException("File " + srcPath + " was not found inside JAR.");
        }
    }

    private static String sourceDir(String path) {
        String dirPath = (String) path.subSequence(0, path.lastIndexOf("/"));
        return dirPath;
    }

    private static String baseName(String path) {
        String[] parts = path.split("/");
        return (parts.length > 1) ? parts[parts.length - 1] : null;
    }

    private static boolean isPosixCompliant() {
        try {
            return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (FileSystemNotFoundException
                | ProviderNotFoundException
                | SecurityException e) {
            return false;
        }
    }

    private static File createTempDirectory() throws IOException {
        final String tempDir = System.getProperty("java.io.tmpdir");
        final File generatedDir = new File(tempDir, NATIVE_FOLDER_PATH_PREFIX + System.nanoTime());
        if (!generatedDir.mkdir()) {
            throw new IOException("Failed to create temp directory " + generatedDir.getName());
        }
        return generatedDir;
    }
}