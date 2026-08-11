// Reports which ArbitraryIntrospector can build a type, and which properties each one
// leaves unwritten. Run with the project's test runtime classpath:
//
//   jshell -q --class-path "$CP" -R-Dfm.targets=com.example.Order introspector-probe.jsh
//
// System properties:
//   fm.targets       comma-separated fully qualified class names (required)
//   fm.plugins       comma-separated Plugin class names to apply, e.g. JacksonPlugin
//   fm.introspector  probe only this introspector (simple name or FQCN)
//   fm.failover      comma-separated list to probe as one FailoverIntrospector chain
//
// Every result line is prefixed FM> so it survives grep past logger noise.
//
// jshell ends a snippet at a newline once it forms a complete statement, so top-level
// method chains must stay on one line. Chains inside a method body are fine.

import java.lang.reflect.*;
import java.util.*;
import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.FixtureMonkeyBuilder;
import com.navercorp.fixturemonkey.api.arbitrary.CombinableArbitrary;
import com.navercorp.fixturemonkey.api.introspector.ArbitraryIntrospector;
import com.navercorp.fixturemonkey.api.introspector.ArbitraryIntrospectorResult;
import com.navercorp.fixturemonkey.api.introspector.FailoverIntrospector;
import com.navercorp.fixturemonkey.api.plugin.Plugin;

String OUT = "FM>";
int SAMPLES = 5;

String[] INTROSPECTOR_PACKAGES = {
	"com.navercorp.fixturemonkey.api.introspector.",
	"com.navercorp.fixturemonkey.kotlin.introspector.",
	"com.navercorp.fixturemonkey.jackson.introspector.",
	"com.navercorp.fixturemonkey.mockito.plugin.",
	"com.navercorp.fixturemonkey.datafaker.introspector.",
};

Object loadInstance(String fqcn) {
	try {
		Class<?> type = Class.forName(fqcn);
		try {
			return type.getField("INSTANCE").get(null);
		} catch (NoSuchFieldException e) {
			return type.getDeclaredConstructor().newInstance();
		}
	} catch (Throwable t) {
		return null;
	}
}

ArbitraryIntrospector resolveIntrospector(String name) {
	List<String> tried = new ArrayList<>();
	if (name.indexOf('.') >= 0) {
		tried.add(name);
	} else {
		for (String prefix : INTROSPECTOR_PACKAGES) {
			tried.add(prefix + name);
		}
	}
	for (String fqcn : tried) {
		Object loaded = loadInstance(fqcn);
		if (loaded instanceof ArbitraryIntrospector) {
			return (ArbitraryIntrospector)loaded;
		}
	}
	return null;
}

List<String> csv(String property) {
	List<String> values = new ArrayList<>();
	for (String value : System.getProperty(property, "").split(",")) {
		if (!value.trim().isEmpty()) {
			values.add(value.trim());
		}
	}
	return values;
}

boolean isKotlin(Class<?> type) {
	try {
		Class annotation = Class.forName("kotlin.Metadata");
		return type.getAnnotation(annotation) != null;
	} catch (Throwable t) {
		return false;
	}
}

List<String> defaultCandidates(boolean kotlin) {
	List<String> names = new ArrayList<>();
	if (kotlin) {
		names.add("PrimaryConstructorArbitraryIntrospector");
		names.add("KotlinPropertyArbitraryIntrospector");
		names.add("KotlinAndJavaCompositeArbitraryIntrospector");
	}
	names.add("BeanArbitraryIntrospector");
	names.add("ConstructorPropertiesArbitraryIntrospector");
	names.add("FieldReflectionArbitraryIntrospector");
	names.add("BuilderArbitraryIntrospector");
	names.add("JacksonObjectArbitraryIntrospector");
	names.add("PriorityConstructorArbitraryIntrospector");
	return names;
}

// Sentinel values make "never written" detectable: a numeric or boolean field still
// holding the JVM default was not written, because generation never produces the default.
FixtureMonkeyBuilder withSentinels(FixtureMonkeyBuilder builder) {
	Object[][] sentinels = {
		{boolean.class, Boolean.TRUE}, {Boolean.class, Boolean.TRUE},
		{char.class, 'Z'}, {Character.class, 'Z'},
		{byte.class, (byte)7}, {Byte.class, (byte)7},
		{short.class, (short)7}, {Short.class, (short)7},
		{int.class, 7}, {Integer.class, 7},
		{long.class, 7L}, {Long.class, 7L},
		{float.class, 7.0f}, {Float.class, 7.0f},
		{double.class, 7.0d}, {Double.class, 7.0d},
	};
	FixtureMonkeyBuilder result = builder;
	for (Object[] sentinel : sentinels) {
		Object value = sentinel[1];
		result = result.pushExactTypeArbitraryIntrospector(
			(Class<?>)sentinel[0],
			context -> new ArbitraryIntrospectorResult(CombinableArbitrary.from(value))
		);
	}
	return result;
}

List<Field> instanceFields(Class<?> type) {
	List<Field> fields = new ArrayList<>();
	for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
		for (Field field : current.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
				try {
					field.setAccessible(true);
					fields.add(field);
				} catch (Throwable ignored) {
				}
			}
		}
	}
	return fields;
}

boolean isDefaultValued(Object value) {
	if (value == null) {
		return true;
	}
	if (value instanceof Boolean) {
		return !((Boolean)value);
	}
	if (value instanceof Character) {
		return ((Character)value) == '\0';
	}
	if (value instanceof Number) {
		return ((Number)value).doubleValue() == 0.0d;
	}
	return false;
}

String describe(Throwable thrown) {
	Throwable deepest = thrown;
	for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
		deepest = cause;
		String message = cause.getMessage();
		if (message != null && !message.trim().isEmpty()) {
			String line = cause.getClass().getSimpleName() + ": " + message.trim().replace('\n', ' ');
			return line.length() > 180 ? line.substring(0, 180) + "..." : line;
		}
	}
	for (StackTraceElement frame : deepest.getStackTrace()) {
		if (frame.getClassName().startsWith("com.navercorp")) {
			return deepest.getClass().getSimpleName() + " at " + frame;
		}
	}
	return deepest.getClass().getSimpleName();
}

void report(String label, Class<?> type, List<Field> fields, ArbitraryIntrospector introspector, List<String> plugins) {
	Map<String, Integer> defaultCounts = new LinkedHashMap<>();
	for (Field field : fields) {
		defaultCounts.put(field.getName(), 0);
	}

	String error = null;
	try {
		FixtureMonkeyBuilder builder = FixtureMonkey.builder();
		for (String pluginName : plugins) {
			Object plugin = loadInstance(pluginName);
			if (plugin instanceof Plugin) {
				builder = builder.plugin((Plugin)plugin);
			}
		}
		FixtureMonkey sut = withSentinels(builder).objectIntrospector(introspector).defaultNotNull(true).build();

		for (int i = 0; i < SAMPLES; i++) {
			Object sample = sut.giveMeOne(type);
			if (sample == null) {
				// An introspector that cannot build the type returns null rather than throwing.
				throw new IllegalStateException("generated null -- this introspector does not build the type");
			}
			for (Field field : fields) {
				if (isDefaultValued(field.get(sample))) {
					defaultCounts.put(field.getName(), defaultCounts.get(field.getName()) + 1);
				}
			}
		}
	} catch (Throwable thrown) {
		error = describe(thrown);
	}

	if (error != null) {
		System.out.println(OUT + "   FAIL      " + label + " -- " + error);
		return;
	}

	// Default in every sample means the introspector never wrote it. Default in only
	// some samples is nullable generation, which no introspector choice fixes.
	List<String> never = new ArrayList<>();
	List<String> sometimes = new ArrayList<>();
	for (Map.Entry<String, Integer> entry : defaultCounts.entrySet()) {
		if (entry.getValue() == SAMPLES) {
			never.add(entry.getKey());
		} else if (entry.getValue() > 0) {
			sometimes.add(entry.getKey() + "(" + entry.getValue() + "/" + SAMPLES + ")");
		}
	}

	String suffix = sometimes.isEmpty() ? "" : " -- sometimes null: " + sometimes;
	if (never.isEmpty()) {
		System.out.println(OUT + "   COMPLETE  " + label + suffix);
	} else {
		System.out.println(OUT + "   PARTIAL   " + label + " -- never written: " + never + suffix);
	}
}

void probe(String className, List<String> requestedPlugins, String only, List<String> failover) {
	Class<?> type;
	try {
		type = Class.forName(className);
	} catch (Throwable t) {
		System.out.println(OUT + " TYPE " + className + " NOT_ON_CLASSPATH");
		return;
	}

	boolean kotlin = isKotlin(type);
	List<Field> fields = instanceFields(type);
	System.out.println(OUT + " TYPE " + className + (kotlin ? " [kotlin]" : "") + " fields=" + fields.size());
	if (fields.isEmpty()) {
		System.out.println(OUT + "   SKIP no instance fields to measure");
		return;
	}

	List<String> plugins = new ArrayList<>(requestedPlugins);
	if (kotlin) {
		plugins.add(0, "com.navercorp.fixturemonkey.kotlin.KotlinPlugin");
	}

	if (!failover.isEmpty()) {
		List<ArbitraryIntrospector> chain = new ArrayList<>();
		List<String> resolved = new ArrayList<>();
		for (String name : failover) {
			ArbitraryIntrospector introspector = resolveIntrospector(name);
			if (introspector == null) {
				System.out.println(OUT + "   SKIP not on classpath: " + name);
				return;
			}
			chain.add(introspector);
			resolved.add(name.substring(name.lastIndexOf('.') + 1));
		}
		report("Failover" + resolved, type, fields, new FailoverIntrospector(chain, false), plugins);
		return;
	}

	List<String> candidates = only == null ? defaultCandidates(kotlin) : Collections.singletonList(only);
	for (String name : candidates) {
		ArbitraryIntrospector introspector = resolveIntrospector(name);
		if (introspector == null) {
			if (only != null) {
				System.out.println(OUT + "   SKIP not on classpath: " + name);
			}
			continue;
		}
		report(name.substring(name.lastIndexOf('.') + 1), type, fields, introspector, plugins);
	}
}

List<String> targets = csv("fm.targets");
List<String> requestedPlugins = csv("fm.plugins");
List<String> failover = csv("fm.failover");
String only = System.getProperty("fm.introspector", "").trim();
if (only.isEmpty()) {
	only = null;
}

if (targets.isEmpty()) {
	System.out.println(OUT + " no fm.targets given");
} else {
	for (String target : targets) {
		probe(target, requestedPlugins, only, failover);
	}
}

/exit
