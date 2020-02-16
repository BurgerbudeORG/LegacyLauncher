package net.minecraft.launchwrapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The class loader to inject transformers in to.
 *
 * @author cpw, Erik Broes, Mumfrey and Nathan Adams
 */

public class LaunchClassLoader extends URLClassLoader {

    private static final Logger LOGGER = LogManager.getLogger("LaunchWrapper");

    /**
     * The size of the chunks when fully reading a stream.
     */
    private static final int BUFFER_SIZE = 1 << 12;
    private List<URL> sources;
    private ClassLoader parent = getClass().getClassLoader();

    private List<IClassTransformer> transformers = new ArrayList<>(2);
    private Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    private Set<String> invalidClasses = new HashSet<>(1000);

    private Set<String> classLoaderExceptions = new HashSet<>();
    private Set<String> transformerExceptions = new HashSet<>();
    private Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1000);
    private Set<String> negativeResourceCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private IClassNameTransformer renameTransformer;

    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<>();

    private static final String[] RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5",
            "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private static final boolean DEBUG = Boolean.parseBoolean(
            System.getProperty("legacy.debugClassLoading", "false"));
    private static final boolean DEBUG_FINER = DEBUG && Boolean.parseBoolean(
            System.getProperty("legacy.debugClassLoadingFiner", "false"));
    private static final boolean DEBUG_SAVE = DEBUG && Boolean.parseBoolean(
            System.getProperty("legacy.debugClassLoadingSave", "false"));
    private static File tempFolder = null;

    /**
     * Creates a new {@link LaunchClassLoader}
     *
     * @param sources The URLs to load from.
     */

    public LaunchClassLoader(URL[] sources) {
        super(sources, null);
        this.sources = new ArrayList<>(Arrays.asList(sources));

        // classloader exclusions
        addClassLoaderExclusion("java.");
        addClassLoaderExclusion("sun.");
        addClassLoaderExclusion("com.sun.");
        addClassLoaderExclusion("org.lwjgl.");
        addClassLoaderExclusion("org.apache.logging.");
        addClassLoaderExclusion("org.fusesource.jansi.");
        addClassLoaderExclusion("net.minecraft.launchwrapper.");

        // transformer exclusions
        addTransformerExclusion("javax.");
        addTransformerExclusion("argo.");
        addTransformerExclusion("org.objectweb.asm.");
        addTransformerExclusion("com.google.common.");
        addTransformerExclusion("org.bouncycastle.");

        if (DEBUG_SAVE) {
            int x = 1;
            tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP");
            while (tempFolder.exists() && x <= 10) {
                tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP" + x++);
            }

            if (tempFolder.exists()) {
                LOGGER.info("DEBUG_SAVE enabled, but 10 temp directories already exist," +
                        " clean them and try again.");
                tempFolder = null;
            } else {
                LOGGER.info("DEBUG_SAVE Enabled, saving all classes to \"%s\"",
                        tempFolder.getAbsolutePath().replace('\\', '/'));
                tempFolder.mkdirs();
            }
        }
    }

    /**
     * Registers a transformer. The transformer will be loaded with this class loader, not the parent one.
     *
     * @param transformerClassName The transformer to inject
     */

    public void registerTransformer(String transformerClassName) {
        try {
            IClassTransformer transformer = (IClassTransformer) loadClass(transformerClassName).newInstance();
            transformers.add(transformer);
            if (transformer instanceof IClassNameTransformer && renameTransformer == null) {
                renameTransformer = (IClassNameTransformer) transformer;
            }
        } catch (Exception e) {
            LOGGER.error("A critical problem occurred registering the ASM transformer class {}", transformerClassName, e);
        }
    }

    /**
     * Loads in a class. It is recommended to use {@link ClassLoader#loadClass(String)} instead.
     *
     * @param name The name of the class to load
     */

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) {
            throw new ClassNotFoundException(name);
        }

        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
            }
        }

        if (cachedClasses.containsKey(name)) {
            return cachedClasses.get(name);
        }

        for (final String exception : transformerExceptions) {
            if (name.startsWith(exception)) {
                try {
                    final Class<?> clazz = super.findClass(name);
                    cachedClasses.put(name, clazz);
                    return clazz;
                } catch (ClassNotFoundException e) {
                    invalidClasses.add(name);
                    throw e;
                }
            }
        }

        try {
            final String transformedName = transformName(name);
            if (cachedClasses.containsKey(transformedName)) {
                return cachedClasses.get(transformedName);
            }

            final String untransformedName = untransformName(name);

            final int lastDot = untransformedName.lastIndexOf('.');
            final String packageName = lastDot == -1 ? "" : untransformedName.substring(0, lastDot);
            final String fileName = untransformedName.replace('.', '/').concat(".class");
            URLConnection urlConnection = findCodeSourceConnectionFor(fileName);

            if (urlConnection == null) throw new ClassNotFoundException(name);

            CodeSigner[] signers = null;

            if (lastDot > -1 && !untransformedName.startsWith("net.minecraft.") &&
                    !untransformedName.startsWith("com.mojang.blaze3d.")) {
                if (urlConnection instanceof JarURLConnection) {
                    final JarURLConnection jarURLConnection = (JarURLConnection) urlConnection;
                    final JarFile jarFile = jarURLConnection.getJarFile();

                    if (jarFile != null && jarFile.getManifest() != null) {
                        final Manifest manifest = jarFile.getManifest();
                        final JarEntry entry = jarFile.getJarEntry(fileName);

                        Package pkg = getPackage(packageName);
                        getClassBytes(untransformedName);
                        signers = entry.getCodeSigners();
                        if (pkg != null) {
                            if (pkg.isSealed() && !pkg.isSealed(jarURLConnection.getJarFileURL())) {
                                LOGGER.info("The jar file {} is trying to seal already secured path {}",
                                        jarFile.getName(), packageName);
                            } else if (isSealed(packageName, manifest)) {
                                LOGGER.warn("The jar file {} has a security seal for path {}," +
                                        " but that path is defined and not secure", jarFile.getName(), packageName);
                            }
                        } else {
                            definePackage(packageName, manifest, jarURLConnection.getJarFileURL());
                        }
                    }
                } else {
                    Package pkg = getPackage(packageName);
                    if (pkg != null) {
                        if (pkg.isSealed()) {
                            LOGGER.info("The URL {} is defining elements for sealed path {}",
                                    urlConnection.getURL(), packageName);
                        }
                    } else {
                        definePackage(packageName, null, null, null, null, null, null, null);
                    }
                }
            }

            final byte[] transformedClass = runTransformers(untransformedName, transformedName, getClassBytes(untransformedName));

            if (transformedClass != null) {

                if (DEBUG_SAVE) {
                    saveTransformedClass(transformedClass, transformedName);
                }

                final CodeSource codeSource = new CodeSource(urlConnection.getURL(), signers);
                final Class<?> clazz = defineClass(transformedName, transformedClass, 0,
                        transformedClass.length, codeSource);
                cachedClasses.put(transformedName, clazz);
                return clazz;
            } else {
                throw new ClassNotFoundException(name);
            }

        } catch (Throwable e) {
            invalidClasses.add(name);
            if (DEBUG) {
                LOGGER.trace("Exception encountered attempting classloading of {}", name, e);
                LogManager.getLogger("LaunchWrapper").log(Level.ERROR, "Exception encountered" +
                        " attempting classloading of {}", name, e);
            }

            if (e instanceof ClassNotFoundException) throw (ClassNotFoundException) e;

            throw new ClassNotFoundException(name, e);
        }
    }

    private void saveTransformedClass(final byte[] data, final String transformedName) {
        if (tempFolder == null || data == null) {
            return;
        }

        final File outFile = new File(tempFolder, transformedName
                .replace('.', File.separatorChar) + ".class");
        final File outDir = outFile.getParentFile();

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        if (outFile.exists()) {
            outFile.delete();
        }

        try {
            LOGGER.debug("Saving transformed class \"{}\" to \"{}\"", transformedName,
                    outFile.getAbsolutePath().replace('\\', '/'));

            final OutputStream output = new FileOutputStream(outFile);
            output.write(data);
            output.close();
        } catch (IOException ex) {
            LOGGER.warn("Could not save transformed class \"{}\"", transformedName, ex);
        }
    }

    private String untransformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.unmapClassName(name);
        }

        return name;
    }

    private String transformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.remapClassName(name);
        }

        return name;
    }

    private boolean isSealed(final String path, final Manifest manifest) {
        Attributes attributes = manifest.getAttributes(path);
        String sealed = null;
        if (attributes != null) {
            sealed = attributes.getValue(Name.SEALED);
        }

        if (sealed == null) {
            attributes = manifest.getMainAttributes();
            if (attributes != null) {
                sealed = attributes.getValue(Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);
    }

    private URLConnection findCodeSourceConnectionFor(final String name) {
        final URL resource = findResource(name);
        if (resource != null) {
            try {
                return resource.openConnection();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        if (DEBUG_FINER) {
            LOGGER.trace("Beginning transform of {{} ({})} Start Length: {}",
                    name, transformedName, (basicClass == null ? 0 : basicClass.length));
            for (final IClassTransformer transformer : transformers) {
                final String transName = transformer.getClass().getName();
                LOGGER.trace("Before Transformer {{} ({})} {}: {}", name,
                        transformedName, transName, (basicClass == null ? 0 : basicClass.length));
                basicClass = transformer.transform(name, transformedName, basicClass);
                LOGGER.trace("After  Transformer {{} ({})} {}: {}", name,
                        transformedName, transName, (basicClass == null ? 0 : basicClass.length));
            }
            LOGGER.trace("Ending transform of {{} ({})} Start Length: {}", name,
                    transformedName, (basicClass == null ? 0 : basicClass.length));
        } else {
            for (final IClassTransformer transformer : transformers) {
                basicClass = transformer.transform(name, transformedName, basicClass);
            }
        }
        return basicClass;
    }

    /**
     * Adds an URL for the class loader to check
     *
     * @param url The url to add
     */

    @Override
    public void addURL(final URL url) {
        super.addURL(url);
        sources.add(url);
    }

    /**
     * Gets the URL list
     *
     * @return A list of URLs that this class loader checks.
     */

    public List<URL> getSources() {
        return sources;
    }

    private byte[] readFully(InputStream stream) {
        try {
            byte[] buffer = getOrCreateBuffer();

            int read;
            int totalLength = 0;
            while ((read = stream.read(buffer, totalLength, buffer.length - totalLength)) != -1) {
                totalLength += read;

                // Extend our buffer
                if (totalLength >= buffer.length - 1) {
                    byte[] newBuffer = new byte[buffer.length + BUFFER_SIZE];
                    System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                    buffer = newBuffer;
                }
            }

            final byte[] result = new byte[totalLength];
            System.arraycopy(buffer, 0, result, 0, totalLength);
            return result;
        } catch (Throwable t) {
            LOGGER.warn("Problem loading class", t);
            return new byte[0];
        }
    }

    private byte[] getOrCreateBuffer() {
        byte[] buffer = loadBuffer.get();
        if (buffer == null) {
            loadBuffer.set(new byte[BUFFER_SIZE]);
            buffer = loadBuffer.get();
        }
        return buffer;
    }

    /**
     * Gets the transformers injected.
     *
     * @return An immutable list of transformers.
     */

    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    /**
     * Adds the given prefix to the class loader exclusion list. Classes that have a prefix in this list will
     * be loaded with the parent class loader
     *
     * @param toExclude The prefix to exclude
     */

    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
    }

    /**
     * Adds the given prefix to the transformer exclusion list. Classes that have a prefix in this list will
     * be loaded without transformers being run on them.
     *
     * @param toExclude The prefix to exclude.
     */

    public void addTransformerExclusion(String toExclude) {
        transformerExceptions.add(toExclude);
    }

    /**
     * Gets the bytecode of a class.
     *
     * @param name The name of the class
     * @return The untransformed bytecode of the class.
     * @throws IOException If the class loader fails to open a connection to the URL.
     */

    public byte[] getClassBytes(String name) throws IOException {
        if (negativeResourceCache.contains(name)) {
            return null;
        } else if (resourceCache.containsKey(name)) {
            return resourceCache.get(name);
        }
        if (name.indexOf('.') == -1) {
            for (final String reservedName : RESERVED_NAMES) {
                if (name.toUpperCase(Locale.ENGLISH).startsWith(reservedName)) {
                    final byte[] data = getClassBytes("_" + name);
                    if (data != null) {
                        resourceCache.put(name, data);
                        return data;
                    }
                }
            }
        }

        InputStream classStream = null;
        try {
            final String resourcePath = name.replace('.', '/').concat(".class");
            final URL classResource = findResource(resourcePath);

            if (classResource == null) {
                if (DEBUG) LOGGER.trace("Failed to find class resource {}", resourcePath);
                negativeResourceCache.add(name);
                return null;
            }
            classStream = classResource.openStream();

            if (DEBUG) LOGGER.trace("Loading class {} from resource {}", name, classResource.toString());
            final byte[] data = readFully(classStream);
            resourceCache.put(name, data);
            return data;
        } finally {
            closeSilently(classStream);
        }
    }

    private static void closeSilently(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Removes entries that were previously marked invalid.
     *
     * @param entriesToClear The entries to remove
     */

    public void clearNegativeEntries(Set<String> entriesToClear) {
        negativeResourceCache.removeAll(entriesToClear);
    }
}
