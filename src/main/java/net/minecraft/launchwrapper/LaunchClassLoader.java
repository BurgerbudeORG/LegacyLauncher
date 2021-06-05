package net.minecraft.launchwrapper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The class loader to inject transformers in to.
 *
 * @author cpw, Erik Broes, Mumfrey and Nathan Adams
 */
public class LaunchClassLoader extends URLClassLoader {

  private static final Logger LOGGER = LogManager.getLogger("LaunchWrapper");
  /** The size of the chunks when fully reading a stream. */
  private static final int BUFFER_SIZE = 1 << 12;

  private static final String[] RESERVED_NAMES = {
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
  };
  private static final boolean DEBUG =
      Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
  private static final boolean DEBUG_FINER =
      DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingFiner", "false"));
  private static final boolean DEBUG_SAVE =
      DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));
  private static File tempFolder = null;

  static {
    ClassLoader.registerAsParallelCapable();
  }

  private final ThreadLocal<byte[]> loadBuffer =
      ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);
  private final List<URL> sources;
  private final ClassLoader parent = getClass().getClassLoader();
  private final List<IClassTransformer> transformers = new ArrayList<>(2);
  private final Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
  private final Set<String> invalidClasses = new HashSet<>(1000);
  private final Set<String> classLoaderExceptions = new HashSet<>();
  private final Set<String> transformerExceptions = new HashSet<>();
  private final Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1000);
  private final Set<String> negativeResourceCache =
      Collections.newSetFromMap(new ConcurrentHashMap<>());
  private IClassNameTransformer renameTransformer;

  /**
   * Creates a new {@link LaunchClassLoader}
   *
   * @param sources The URLs to load from.
   */
  @SuppressWarnings("ResultOfMethodCallIgnored")
  public LaunchClassLoader(URL[] sources) {
    super(sources, null);
    this.sources = new ArrayList<>(Arrays.asList(sources));

    // classloader exclusions
    addClassLoaderExclusion("java.");
    addClassLoaderExclusion("jdk.");
    addClassLoaderExclusion("sun.");
    addClassLoaderExclusion("joptsimple.");
    addClassLoaderExclusion("org.slf4j.");
    addClassLoaderExclusion("com.sun.jndi.");
    addClassLoaderExclusion("net.minecraft.launchwrapper.");

    // transformer exclusions
    addTransformerExclusion("jdk.");
    addTransformerExclusion("javax.");
    addTransformerExclusion("org.objectweb.asm.");

    ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

    // By default LaunchWrapper inherits the class path from the system class loader.
    // However, JRE extensions (e.g Nashorn in the jre/lib/ext directory) are not part of the class
    // path of the system class loader.
    //
    // Instead, they're loaded using a parent class loader. Currently, LaunchWrapper does not fall
    // back to the parent class loader if it's unable to find a class on its class path. To make
    // the JRE extensions usable for plugins we manually add the URL's from the ExtClassLoader to
    // LaunchWrapper's class path.
    if (systemClassLoader != null) {
      systemClassLoader = systemClassLoader.getParent();

      if (systemClassLoader instanceof URLClassLoader) {
        Arrays.stream(((URLClassLoader) systemClassLoader).getURLs()).forEach(this::addURL);
      }
    }

    if (DEBUG_SAVE) {
      int x = 1;
      tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP");
      while (tempFolder.exists() && x <= 10) {
        tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP" + x++);
      }

      if (tempFolder.exists()) {
        LOGGER.info(
            "DEBUG_SAVE enabled, but 10 temp directories already exist,"
                + " clean them and try again.");
        tempFolder = null;
      } else {
        LOGGER.info(
            "DEBUG_SAVE Enabled, saving all classes to \"{}\"",
            tempFolder.getAbsolutePath().replace('\\', '/'));
        tempFolder.mkdirs();
      }
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
   * Registers a transformer. The transformer will be loaded with this class loader, not the parent
   * one.
   *
   * @param transformerClassName The transformer to inject
   */
  public void registerTransformer(String transformerClassName) {
    try {
      Class<?> transformerClass = loadClass(transformerClassName);

      Set<Constructor<?>> otherConstructors = new HashSet<>();
      Constructor<?> emptyConstructor = null;

      for (final Constructor<?> declaredConstructor : transformerClass.getDeclaredConstructors()) {
        if (declaredConstructor.getParameterCount() == 0) {
          emptyConstructor = declaredConstructor;
          break;
        }
        otherConstructors.add(declaredConstructor);
      }

      if (emptyConstructor == null) {
        LOGGER.error(
            "Could not register the Transformer because it did not have an empty constructor!");
        LOGGER.error("Found constructors:");
        otherConstructors.stream()
            .map(otherConstructor -> "\tParameter Count: " + otherConstructor.getParameterCount())
            .forEach(LOGGER::error);
        otherConstructors.clear();
        return;
      }

      IClassTransformer transformer = (IClassTransformer) emptyConstructor.newInstance();
      this.transformers.add(transformer);
      if (transformer instanceof IClassNameTransformer && this.renameTransformer == null) {
        this.renameTransformer = (IClassNameTransformer) transformer;
      }
    } catch (Exception exception) {
      LOGGER.error(
          "A critical problem occurred registering the ASM transformer class {}",
          transformerClassName,
          exception);
    }
  }

  /**
   * Loads in a class. It is recommended to use {@link ClassLoader#loadClass(String)} instead.
   *
   * @param name The name of the class to load
   */
  @Override
  public Class<?> findClass(final String name) throws ClassNotFoundException {
    if (this.invalidClasses.contains(name)) {
      throw new ClassNotFoundException(name);
    }

    for (final String exception : this.classLoaderExceptions) {
      if (name.startsWith(exception)) return parent.loadClass(name);
    }

    if (this.cachedClasses.containsKey(name)) return this.cachedClasses.get(name);

    for (final String exception : this.transformerExceptions) {
      if (name.startsWith(exception)) {
        try {
          final Class<?> clazz = super.findClass(name);
          this.cachedClasses.put(name, clazz);
          return clazz;
        } catch (ClassNotFoundException e) {
          this.invalidClasses.add(name);
          throw e;
        }
      }
    }

    final String transformedName = transformName(name);
    if (this.cachedClasses.containsKey(transformedName)) {
      return this.cachedClasses.get(transformedName);
    }

    final String untransformedName = untransformName(name);

    // Get class bytes
    byte[] classData = getClassBytes(untransformedName);

    byte[] transformedClass;
    try {
      // Run transformers (running with null class bytes is valid, because transformers may generate
      // classes dynamically)
      transformedClass = runTransformers(untransformedName, transformedName, classData);
    } catch (Exception exception) {
      LOGGER.trace("Exception encountered while transformimg class {}", name, exception);
      throw exception;
    }

    // If transformer chain provides no class data, mark given class name invalid and throw CNFE
    if (transformedClass == null) {
      invalidClasses.add(name);
      throw new ClassNotFoundException(name);
    }

    // Save class if requested so
    if (DEBUG_SAVE) {
      saveTransformedClass(transformedClass, transformedName);
    }

    // Define package for class
    int lastDot = untransformedName.lastIndexOf('.');
    String packageName = lastDot == -1 ? "" : untransformedName.substring(0, lastDot);
    String fileName = untransformedName.replace('.', '/').concat(".class");
    URLConnection urlConnection = findCodeSourceConnectionFor(fileName);
    CodeSigner[] signers = null;

    try {
      if (lastDot > -1
          && (!untransformedName.startsWith("net.minecraft.")
              && !untransformedName.startsWith("com.mojang."))) {
        if (urlConnection instanceof final JarURLConnection jarURLConnection) {
          final JarFile jarFile = jarURLConnection.getJarFile();

          if (jarFile != null && jarFile.getManifest() != null) {
            final Manifest manifest = jarFile.getManifest();
            final JarEntry entry = jarFile.getJarEntry(fileName);

            Package pkg = getPackage(packageName);
            getClassBytes(untransformedName);
            signers = entry.getCodeSigners();
            if (pkg == null) {
              pkg = definePackage(packageName, manifest, jarURLConnection.getJarFileURL());
            } else {
              if (pkg.isSealed() && !pkg.isSealed(jarURLConnection.getJarFileURL())) {
                LOGGER.error(
                    "The jar file {} is trying to seal already secured path {}",
                    jarFile.getName(),
                    packageName);
              } else if (isSealed(packageName, manifest)) {
                LOGGER.error(
                    "The jar file {} has a security seal for path {}, but that path is defined and not secure",
                    jarFile.getName(),
                    packageName);
              }
            }
          }
        } else {
          Package pkg = getPackage(packageName);
          if (pkg == null) {
            pkg = definePackage(packageName, null, null, null, null, null, null, null);
          } else if (pkg.isSealed()) {
            URL url = urlConnection != null ? urlConnection.getURL() : null;
            LOGGER.error("The URL {} is defining elements for sealed path {}", url, packageName);
          }
        }
      }

      // Define class
      final CodeSource codeSource =
          urlConnection == null ? null : new CodeSource(urlConnection.getURL(), signers);
      final Class<?> cls =
          defineClass(transformedName, transformedClass, 0, transformedClass.length, codeSource);
      cachedClasses.put(transformedName, cls);
      return cls;
    } catch (Exception e) {
      invalidClasses.add(name);
      if (DEBUG) LOGGER.trace("Exception encountered attempting classloading of {}", name, e);
      throw new ClassNotFoundException(name, e);
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void saveTransformedClass(final byte[] data, final String transformedName) {
    if (tempFolder == null || data == null) {
      return;
    }

    final File outFile =
        new File(tempFolder, transformedName.replace('.', File.separatorChar) + ".class");
    final File outDir = outFile.getParentFile();

    if (!outDir.exists()) {
      outDir.mkdirs();
    }

    if (outFile.exists()) {
      outFile.delete();
    }

    try {
      LOGGER.debug(
          "Saving transformed class \"{}\" to \"{}\"",
          transformedName,
          outFile.getAbsolutePath().replace('\\', '/'));

      final OutputStream output = new FileOutputStream(outFile);
      output.write(data);
      output.close();
    } catch (IOException ex) {
      LOGGER.warn("Could not save transformed class \"{}\"", transformedName, ex);
    }
  }

  private String untransformName(final String name) {
    return this.renameTransformer != null ? this.renameTransformer.unmapClassName(name) : name;
  }

  private String transformName(final String name) {
    return this.renameTransformer != null ? this.renameTransformer.remapClassName(name) : name;
  }

  private boolean isSealed(final String path, final Manifest manifest) {
    Attributes attributes = manifest.getAttributes(path);
    String sealed = attributes != null ? attributes.getValue(Name.SEALED) : null;

    if (sealed == null) {
      sealed =
          (attributes = manifest.getMainAttributes()) != null
              ? attributes.getValue(Name.SEALED)
              : null;
    }

    return "true".equalsIgnoreCase(sealed);
  }

  private URLConnection findCodeSourceConnectionFor(final String name) {
    final URL resource = findResource(name);
    if (resource != null) {
      try {
        return resource.openConnection();
      } catch (IOException exception) {
        throw new RuntimeException(exception);
      }
    }

    return null;
  }

  private byte[] runTransformers(
      final String name, final String transformedName, byte[] classData) {

    if (DEBUG_FINER) {
      LOGGER.trace(
          "Beginning transform of {{} ({})} Start Length: {}",
          name,
          transformedName,
          (classData == null ? 0 : classData.length));
    }

    for (final IClassTransformer transformer : this.transformers) {
      String transformerName = transformer.getClass().getName();

      if (DEBUG_FINER) {
        LOGGER.trace(
            "Before Transformer {{} ({})} {}: {}",
            name,
            transformedName,
            transformerName,
            (classData == null ? 0 : classData.length));
      }

      classData = transformer.transform(name, transformedName, classData);

      if (DEBUG_FINER) {
        LOGGER.trace(
            "After  Transformer {{} ({})} {}: {}",
            name,
            transformedName,
            transformerName,
            (classData == null ? 0 : classData.length));
      }
    }

    return classData;
  }

  /**
   * Adds an URL for the class loader to check
   *
   * @param url The url to add
   */
  @Override
  public void addURL(final URL url) {
    super.addURL(url);
    this.sources.add(url);
  }

  /**
   * Gets the URL list
   *
   * @return A list of URLs that this class loader checks.
   */
  public List<URL> getSources() {
    return this.sources;
  }

  private byte[] readFully(InputStream stream) {

    try (ByteArrayOutputStream byteArrayOutputStream =
        new ByteArrayOutputStream(stream.available())) {
      int readableBytes;
      byte[] buffer = this.loadBuffer.get();

      while ((readableBytes = stream.read(buffer, 0, buffer.length)) != -1) {
        byteArrayOutputStream.write(buffer, 0, readableBytes);
      }

      return byteArrayOutputStream.toByteArray();
    } catch (Throwable throwable) {
      LOGGER.warn("Problem reading stream fully!", throwable);
      return null;
    }
  }

  /**
   * Gets the transformers injected.
   *
   * @return An immutable list of transformers.
   */
  public List<IClassTransformer> getTransformers() {
    return Collections.unmodifiableList(this.transformers);
  }

  /**
   * Adds the given prefix to the class loader exclusion list. Classes that have a prefix in this
   * list will be loaded with the parent class loader
   *
   * @param toExclude The prefix to exclude
   */
  public void addClassLoaderExclusion(String toExclude) {
    this.classLoaderExceptions.add(toExclude);
  }

  /**
   * Adds the given prefix to the transformer exclusion list. Classes that have a prefix in this
   * list will be loaded without transformers being run on them.
   *
   * @param toExclude The prefix to exclude.
   */
  public void addTransformerExclusion(String toExclude) {
    this.transformerExceptions.add(toExclude);
  }

  /**
   * Gets the bytecode of a class.
   *
   * @param name The name of the class
   * @return The untransformed bytecode of the class.
   */
  public byte[] getClassBytes(String name) {
    if (this.negativeResourceCache.contains(name)) {
      return null;
    } else if (this.resourceCache.containsKey(name)) {
      return this.resourceCache.get(name);
    }
    if (name.indexOf('.') == -1) {
      for (final String reservedName : RESERVED_NAMES) {
        if (name.toUpperCase(Locale.ENGLISH).startsWith(reservedName)) {
          final byte[] data = getClassBytes("_" + name);
          if (data != null) {
            this.resourceCache.put(name, data);
            return data;
          }
        }
      }
    }

    String resourcePath = name.replace('.', '/').concat(".class");
    URL classResource = findResource(resourcePath);
    if (classResource == null) {
      if (DEBUG) {
        LOGGER.trace("Failed to find class resource {}", resourcePath);
      }
      this.negativeResourceCache.add(name);
      return null;
    }
    try (InputStream classStream = classResource.openStream()) {
      if (DEBUG) {
        LOGGER.trace("Loading class {} from resource {}", name, classResource.toString());
      }
      byte[] data = Objects.requireNonNull(readFully(classStream));
      this.resourceCache.put(name, data);
      return data;
    } catch (Exception exception) {
      if (DEBUG) {
        LOGGER.trace("Failed to load class {} from resource {}", name, classResource.toString());
      }
      this.negativeResourceCache.add(name);
      return null;
    }
  }

  /**
   * Removes entries that were previously marked invalid.
   *
   * @param entriesToClear The entries to remove
   */
  public void clearNegativeEntries(Set<String> entriesToClear) {
    this.negativeResourceCache.removeAll(entriesToClear);
  }
}
