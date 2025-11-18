package group.gnometrading.di;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

public abstract class Orchestrator {

    protected static Class<? extends Orchestrator> instanceClass;

    protected final Map<String, Object> singletonCache;
    protected final Map<String, Method> providers;
    protected final Map<String, Orchestrator> providerInstances;
    protected String[] cliArgs;

    public Orchestrator() {
        this.singletonCache = new HashMap<>();
        this.providers = new HashMap<>();
        this.providerInstances = new HashMap<>();
        this.cliArgs = null;
        initialize();
    }

    private void initialize() {
        Method[] methods = this.getClass().getMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Provides.class)) {
                String qualifier = getQualifier(method);
                this.providers.put(qualifier, method);
                this.providerInstances.put(qualifier, this);
            }
        }
    }

    private String getQualifier(Method method) {
        if (method.isAnnotationPresent(Named.class)) {
            return method.getReturnType().getName() + "@" + method.getAnnotation(Named.class).value();
        }
        return method.getReturnType().getName();
    }

    private String getParameterName(Executable executable, int index) {
        var annotations = executable.getParameterAnnotations()[index];
        for (var annotation : annotations) {
            if (annotation instanceof Named) {
                return ((Named) annotation).value();
            }
        }
        return null;
    }

    private Object[] getParameters(Executable executable) {
        var parameterTypes = executable.getParameterTypes();
        return IntStream.range(0, parameterTypes.length)
                .mapToObj(index -> getInstance(parameterTypes[index], getParameterName(executable, index)))
                .toArray();
    }

    private <T> T callConstructor(Class<T> type, Constructor<?> constructor, String qualifier) {
        try {
            constructor.setAccessible(true);
            var params = getParameters(constructor);
            T instance = type.cast(constructor.newInstance(params));
            if (constructor.isAnnotationPresent(Singleton.class)) {
                singletonCache.put(qualifier, instance);
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance for class: " + type.getName(), e);
        }
    }

    protected <T> T getInstance(Class<T> type) {
        return getInstance(type, null);
    }

    protected <T> T getInstance(Class<T> type, String name) {
        String qualifier = name != null ? type.getName() + "@" + name : type.getName();
        if (singletonCache.containsKey(qualifier)) {
            return type.cast(singletonCache.get(qualifier));
        }

        if (providers.containsKey(qualifier)) {
            Method provider = providers.get(qualifier);
            Orchestrator owner = providerInstances.get(qualifier);
            try {
                provider.setAccessible(true);
                var params = getParameters(provider);
                T instance = type.cast(provider.invoke(owner, params));
                if (provider.isAnnotationPresent(Singleton.class)) {
                    singletonCache.put(qualifier, instance);
                }
                return instance;
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke provider for class: " + type.getName(), e);
            }
        }

        try {
            var constructors = type.getConstructors();
            for (var constructor : constructors) {
                if (constructor.isAnnotationPresent(Inject.class)) {
                    return callConstructor(type, constructor, qualifier);
                }
            }

            // Fallback to default constructor if no @Inject
            var defaultConstructor = type.getDeclaredConstructor();
            return callConstructor(type, defaultConstructor, qualifier);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance for class: " + type.getName(), e);
        }
    }

    /**
     * Create a child orchestrator that inherits all the bindings of the parent.
     *
     * @param orchestratorClass the child orchestrator class
     * @param <T> the child orchestrator type
     * @return the child orchestrator instance
     */
    protected <T extends Orchestrator> T createChildOrchestrator(Class<T> orchestratorClass) {
        try {
            T child = orchestratorClass.getDeclaredConstructor().newInstance();
            child.providers.putAll(this.providers);
            child.providerInstances.putAll(this.providerInstances);
            child.singletonCache.putAll(this.singletonCache);
            child.cliArgs = this.cliArgs;

            child.configure();

            return child;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create child orchestrator for class: " + orchestratorClass.getName(), e);
        }
    }

    @Provides
    @Named("CLI_ARGS")
    public String[] provideCLIArgs() {
        return this.cliArgs;
    }

    public void configure() { /* Default NO-OP */ }

    public static void main(String[] args) throws Exception {
        Orchestrator orchestrator = instanceClass.getDeclaredConstructor().newInstance();
        orchestrator.cliArgs = args;
        orchestrator.configure();
    }
}
