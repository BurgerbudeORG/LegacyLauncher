package net.minecraft.launchwrapper;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * The main class of the launch wrapper
 *
 * @author cpw, Erik Broes, Mumfrey and Nathan Adams
 */

public class Launch {

    private static final Logger LOGGER = LogManager.getLogger("LaunchWrapper");

    /**
     * The game directory of Minecraft. Changing this will change the game directory value passed to tweakers.
     */
    public static File minecraftHome;

    /**
     * The asset directory of Minecraft. Changing this will change the assets directory value passed to tweakers.
     */

    public static File assetsDir;

    public static Map<String, Object> blackboard;

    /**
     * The main method. Please don't invoke this.
     *
     * @param args The command line arguments
     */
    public static void main(String[] args) {
        new Launch().launch(args);
    }

    /**
     * The class loader to register transformers to. Changing this will change the class loader passed to tweakers.
     */

    public static LaunchClassLoader classLoader;

    /**
     * Creates a new instance of Launch. This is only used in the main method.
     */

    private Launch() {
        if (getClass().getClassLoader() instanceof URLClassLoader) {
            URLClassLoader urlClassLoader = (URLClassLoader) getClass().getClassLoader();
            classLoader = new LaunchClassLoader(urlClassLoader.getURLs());
        } else {
            classLoader = new LaunchClassLoader(urls());
        }

        blackboard = new HashMap<>();
        Thread.currentThread().setContextClassLoader(classLoader);
    }

    private URL[] urls() {
        String classPath = System.getProperty("java.class.path");
        String[] elements = classPath.split(File.pathSeparator);

        if (elements.length == 0) elements = new String[]{""};

        URL[] urls = new URL[elements.length];

        for (int i = 0; i < elements.length; i++) {
            try {
                URL url = new URL(elements[i]).toURI().toURL();
                urls[i] = url;
            } catch (MalformedURLException | URISyntaxException e) {
                e.printStackTrace();
            }
        }

        return urls;
    }

    /**
     * Runs the launch wrapper. This is only used in the main method.
     *
     * @param args The command line arguments.
     */

    private void launch(String[] args) {
        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        final OptionSpec<String> profileOption = parser.accepts("version",
                "The version we launched with").withRequiredArg();
        final OptionSpec<File> gameDirOption = parser.accepts("gameDir",
                "Alternative game directory").withRequiredArg().ofType(File.class);
        final OptionSpec<File> assetsDirOption = parser.accepts("assetsDir",
                "Assets directory").withRequiredArg().ofType(File.class);
        final OptionSpec<String> tweakClassOption = parser.accepts("tweakClass",
                "Tweak class(es) to load").withRequiredArg();
        final OptionSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        minecraftHome = options.valueOf(gameDirOption);
        assetsDir = options.valueOf(assetsDirOption);
        final String profileName = options.valueOf(profileOption);
        final List<String> tweakClassNames = new ArrayList<>(options.valuesOf(tweakClassOption));

        final List<String> argumentList = new ArrayList<>();
        // This list of names will be interacted with through tweakers. They can append to this list
        // any 'discovered' tweakers from their preferred mod loading mechanism
        // By making this object discoverable and accessible it's possible to perform
        // things like cascading of tweakers
        blackboard.put("TweakClasses", tweakClassNames);

        // This argument list will be constructed from all tweakers. It is visible here so
        // all tweakers can figure out if a particular argument is present, and add it if not
        blackboard.put("ArgumentList", argumentList);

        // This is to prevent duplicates - in case a tweaker decides to add itself or something
        final Set<String> allTweakerNames = new HashSet<>();
        // The 'definitive' list of tweakers
        final List<ITweaker> allTweakers = new ArrayList<>();
        try {
            final List<ITweaker> tweakers = new ArrayList<ITweaker>(tweakClassNames.size() + 1);
            // The list of tweak instances - may be useful for interoperability
            blackboard.put("Tweaks", tweakers);
            // The primary tweaker (the first one specified on the command line) will actually
            // be responsible for providing the 'main' name and generally gets called first
            ITweaker primaryTweaker = null;
            // This loop will terminate, unless there is some sort of pathological tweaker
            // that reinserts itself with a new identity every pass
            // It is here to allow tweakers to "push" new tweak classes onto the 'stack' of
            // tweakers to evaluate allowing for cascaded discovery and injection of tweakers
            do {

                while (!tweakClassNames.isEmpty()) {
                    final String tweakName = tweakClassNames.remove(0);

                    // Safety check - don't reprocess something we've already visited
                    if (allTweakerNames.contains(tweakName)) {
                        LOGGER.warn("Tweak class name {} has already been visited -- skipping", tweakName);
                        continue;
                    } else {
                        allTweakerNames.add(tweakName);
                    }
                    LOGGER.info("Loading tweak class name {}", tweakName);

                    // Ensure we allow the tweak class to load with the parent classloader
                    classLoader.addClassLoaderExclusion(tweakName.substring(0,tweakName.lastIndexOf('.')));
                    final ITweaker tweaker = (ITweaker) Class.forName(tweakName, true, classLoader).getDeclaredConstructor().newInstance();
                    tweakers.add(tweaker);
                    // If we haven't visited a tweaker yet, the first will become the 'primary' tweaker
                    if (primaryTweaker == null) {
                        LOGGER.info("Using primary tweak class name {}", tweakName);
                        primaryTweaker = tweaker;
                    }

                }

                // Now, iterate all the tweakers we just instantiated
                while (!tweakers.isEmpty()) {
                    final ITweaker tweaker = tweakers.remove(0);
                    LOGGER.info("Calling tweak class {}", tweaker.getClass().getName());
                    tweaker.acceptOptions(options.valuesOf(nonOption), minecraftHome, assetsDir, profileName);
                    tweaker.injectIntoClassLoader(classLoader);
                    allTweakers.add(tweaker);
                }
                // continue around the loop until there's no tweak classes
            } while (!tweakClassNames.isEmpty());

            // Once we're done, we then ask all the tweakers for their arguments and add them all to the
            // master argument list
            allTweakers.stream().map(tweaker -> Arrays.asList(tweaker.getLaunchArguments()))
                    .forEach(argumentList::addAll);

            // Finally we turn to the primary tweaker, and let it tell us where to go to launch
            final String launchTarget = primaryTweaker.getLaunchTarget();
            final Class<?> clazz = Class.forName(launchTarget, false, classLoader);
            final Method mainMethod = clazz.getMethod("main", String[].class);

            LOGGER.info("Launching wrapped minecraft {{}}", launchTarget);
            mainMethod.invoke(null, (Object) argumentList.toArray(new String[argumentList.size()]));
        } catch (Exception e) {
            LOGGER.error("Unable to launch", e);
            System.exit(1);
        }
    }
}
