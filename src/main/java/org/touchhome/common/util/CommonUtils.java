package org.touchhome.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.w3c.dom.Document;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

@Log4j2
public final class CommonUtils {
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static Map<String, ClassLoader> bundleClassLoaders = new HashMap<>();

    @Getter
    private static Path rootPath = SystemUtils.IS_OS_WINDOWS ? SystemUtils.getUserHome().toPath().resolve("touchhome") :
            CommonUtils.createDirectoriesIfNotExists(Paths.get("/opt/touchhome"));
    @Getter
    private static Path tmpPath = CommonUtils.createDirectoriesIfNotExists(rootPath.resolve("tmp"));

    public static void addClassLoader(String bundleName, ClassLoader classLoader) {
        bundleClassLoaders.put(bundleName, classLoader);
    }

    public static void removeClassLoader(String bundleName) {
        bundleClassLoaders.remove(bundleName);
    }

    public static String getErrorMessage(Throwable ex) {
        if (ex == null) {
            return null;
        }
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        if (cause instanceof NullPointerException) {
            return "Unexpected NullPointerException at line: " + ex.getStackTrace()[0].toString();
        }

        return StringUtils.defaultString(cause.getMessage(), cause.toString());
    }

    @SneakyThrows
    public static String getResourceAsString(String bundle, String resource) {
        return IOUtils.toString(getResource(bundle, resource), Charset.defaultCharset());
    }

    @SneakyThrows
    public static <T> List<T> readJSON(String resource, Class<T> targetClass) {
        Enumeration<URL> resources = CommonUtils.class.getClassLoader().getResources(resource);
        List<T> list = new ArrayList<>();
        while (resources.hasMoreElements()) {
            list.add(OBJECT_MAPPER.readValue(resources.nextElement(), targetClass));
        }
        return list;
    }

    public static void addToListSafe(List<String> list, String value) {
        if (!value.isEmpty()) {
            list.add(value);
        }
    }

    public static Path createDirectoriesIfNotExists(Path path) {
        if (Files.notExists(path)) {
            try {
                Files.createDirectories(path);
            } catch (Exception ex) {
                log.error("Unable to create path: <{}>", path.toAbsolutePath().toString());
            }
        }
        return path;
    }

    public static Map<String, String> readPropertiesMerge(String path) {
        Map<String, String> map = new HashMap<>();
        readProperties(path).forEach(map::putAll);
        return map;
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    public static List<String> readFile(String fileName) {
        try {
            return IOUtils.readLines(CommonUtils.class.getClassLoader().getResourceAsStream(fileName), Charset.defaultCharset());
        } catch (Exception ex) {
            log.error(getErrorMessage(ex), ex);

        }
        return Collections.emptyList();
    }

    @SneakyThrows
    public static FileSystem getOrCreateNewFileSystem(String fileSystemPath) {
        if (fileSystemPath == null) {
            return FileSystems.getDefault();
        }
        try {
            return FileSystems.getFileSystem(URI.create(fileSystemPath));
        } catch (Exception ex) {
            return FileSystems.newFileSystem(URI.create(fileSystemPath), Collections.emptyMap());
        }
    }

    @SneakyThrows
    private static List<Map<String, String>> readProperties(String path) {
        Enumeration<URL> resources = CommonUtils.class.getClassLoader().getResources(path);
        List<Map<String, String>> properties = new ArrayList<>();
        while (resources.hasMoreElements()) {
            try (InputStream input = resources.nextElement().openStream()) {
                Properties prop = new Properties();
                prop.load(input);
                properties.add(new HashMap(prop));
            }
        }
        return properties;
    }

    @SneakyThrows
    public static <T> T newInstance(Class<T> clazz) {
        Constructor<T> constructor = findObjectConstructor(clazz);
        if (constructor != null) {
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
        return null;
    }

    @SneakyThrows
    public static <T> Constructor<T> findObjectConstructor(Class<T> clazz, Class<?>... parameterTypes) {
        if (parameterTypes.length > 0) {
            return clazz.getConstructor(parameterTypes);
        }
        for (Constructor<?> constructor : clazz.getConstructors()) {
            if (constructor.getParameterCount() == 0) {
                constructor.setAccessible(true);
                return (Constructor<T>) constructor;
            }
        }
        return null;
    }

    // consume file name with thymaleaf...
    public static TemplateBuilder templateBuilder(String templateName) {
        return new TemplateBuilder(templateName);
    }

    @SneakyThrows
    public static String toString(Document document) {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        return out.toString();
    }

    @SneakyThrows
    public static URL getResource(String bundle, String resource) {
        if (bundle != null && bundleClassLoaders.containsKey(bundle)) {
            return bundleClassLoaders.get(bundle).getResource(resource);
        }
        URL resourceURL = null;
        ArrayList<URL> urls = Collections.list(CommonUtils.class.getClassLoader().getResources(resource));
        if (urls.size() == 1) {
            resourceURL = urls.get(0);
        } else if (urls.size() > 1 && bundle != null) {
            resourceURL = urls.stream().filter(url -> url.getFile().contains(bundle)).findAny().orElse(null);
        }
        return resourceURL;
    }

    @SneakyThrows
    public static <T> T readAndMergeJSON(String resource, T targetObject) {
        ObjectReader updater = OBJECT_MAPPER.readerForUpdating(targetObject);
        ArrayList<ClassLoader> classLoaders = new ArrayList<>(bundleClassLoaders.values());
        classLoaders.add(CommonUtils.class.getClassLoader());

        for (ClassLoader classLoader : classLoaders) {
            for (URL url : Collections.list(classLoader.getResources(resource))) {
                updater.readValue(url);
            }
        }
        return targetObject;
    }

    public static boolean deleteDirectory(Path path) {
        try {
            FileUtils.deleteDirectory(path.toFile());
            return true;
        } catch (IOException ex) {
            log.error("Unable to delete directory: <{}>", path, ex);
        }
        return false;
    }

    public static class TemplateBuilder {
        private final Context context = new Context();
        private final TemplateEngine templateEngine;
        private final String templateName;

        TemplateBuilder(String templateName) {
            this.templateName = templateName;
            this.templateEngine = new TemplateEngine();
            ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
            templateResolver.setTemplateMode(TemplateMode.HTML);
            templateEngine.setTemplateResolver(templateResolver);
        }

        public TemplateBuilder set(String key, Object value) {
            context.setVariable(key, value);
            return this;
        }

        public String build() {
            StringWriter stringWriter = new StringWriter();
            templateEngine.process("templates/" + templateName, context, stringWriter);
            return stringWriter.toString();
        }
    }
}
