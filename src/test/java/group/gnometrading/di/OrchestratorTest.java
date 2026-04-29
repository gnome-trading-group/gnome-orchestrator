package group.gnometrading.di;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrchestratorTest {

    static class CountingModule extends Module {
        static int installCount = 0;

        @Provides
        public String provideString() {
            installCount++;
            return "from module";
        }
    }

    static class DependentModule extends Module {
        @Override
        protected Module[] includes() {
            return new Module[] {new CountingModule()};
        }

        @Provides
        public Integer provideInteger(String s) {
            return s.length();
        }
    }

    static class ModuleOrchestrator extends Orchestrator {
        @Override
        public void configure() {
            install(new CountingModule());
        }
    }

    static class TransitiveModuleOrchestrator extends Orchestrator {
        @Override
        public void configure() {
            install(new DependentModule());
        }
    }

    static class OwnProviderOrchestrator extends Orchestrator {
        @Provides
        public String provideString() {
            return "from orchestrator";
        }

        @Override
        public void configure() {
            install(new CountingModule());
        }
    }

    static class ChildOrchestrator extends Orchestrator {
        @Override
        public void configure() {
            install(new DependentModule());
        }
    }

    static class ParentOrchestrator extends Orchestrator {
        @Override
        public void configure() {
            install(new CountingModule());
        }
    }

    @Test
    void testInstallModule() {
        var orchestrator = new ModuleOrchestrator();
        orchestrator.configure();
        assertEquals("from module", orchestrator.getInstance(String.class));
    }

    @Test
    void testIdempotentInstall() {
        CountingModule.installCount = 0;
        var orchestrator = new ModuleOrchestrator();
        orchestrator.configure();
        orchestrator.install(new CountingModule());
        orchestrator.getInstance(String.class);
        assertEquals(1, CountingModule.installCount);
    }

    @Test
    void testTransitiveIncludes() {
        var orchestrator = new TransitiveModuleOrchestrator();
        orchestrator.configure();
        assertEquals("from module", orchestrator.getInstance(String.class));
        assertEquals(11, orchestrator.getInstance(Integer.class));
    }

    @Test
    void testOrchestratorProviderWinsOverModule() {
        var orchestrator = new OwnProviderOrchestrator();
        orchestrator.configure();
        assertEquals("from orchestrator", orchestrator.getInstance(String.class));
    }

    @Test
    void testChildInheritsParentInstalledModules() {
        CountingModule.installCount = 0;
        var parent = new ParentOrchestrator();
        parent.configure();
        var child = parent.createChildOrchestrator(ChildOrchestrator.class);
        child.getInstance(String.class);
        assertEquals(1, CountingModule.installCount);
    }

    class BasicOrchestrator extends Orchestrator {
        int total = 0;

        @Provides
        public Integer basicProvider() {
            return ++total;
        }

        @Override
        public void configure() {}
    }

    @Test
    void testBasicOrchestrator() {
        var orchestrator = new BasicOrchestrator();

        assertEquals(0, orchestrator.total);
        Integer result = orchestrator.getInstance(Integer.class);
        assertEquals(1, result);
        result = orchestrator.getInstance(Integer.class);
        assertEquals(2, result);
    }

    static class BasicSingletonOrchestrator extends Orchestrator {
        int total = 0;

        @Provides
        @Singleton
        public Integer basicProvider() {
            return ++total;
        }

        @Override
        public void configure() {}
    }

    @Test
    void testBasicSingletonOrchestrator() {
        var orchestrator = new BasicSingletonOrchestrator();

        assertEquals(0, orchestrator.total);
        Integer result = orchestrator.getInstance(Integer.class);
        assertEquals(1, result);
        result = orchestrator.getInstance(Integer.class);
        assertEquals(1, result);
    }

    static class NamedOrchestrator extends Orchestrator {

        @Provides
        @Named("the real real")
        public Integer orchestrator1() {
            return 1;
        }

        @Provides
        @Named("the fake fake")
        public Integer orchestrator2() {
            return 2;
        }

        @Override
        public void configure() {}
    }

    @Test
    void testNamedOrchestrator() {
        var orchestrator = new NamedOrchestrator();

        var result = orchestrator.getInstance(Integer.class, "the real real");
        assertEquals(1, result);
    }

    static class InjectedClass {
        static int mock = 0;

        InjectedClass() {
            mock++;
        }
    }

    static class InjectedSingletonClass {
        static int mock = 0;

        @Singleton
        InjectedSingletonClass() {
            mock++;
        }
    }

    static class DefaultOrchestrator extends Orchestrator {
        @Override
        public void configure() {}
    }

    @Test
    void testInjectDefaultConstructor() {
        var orchestrator = new DefaultOrchestrator();
        InjectedClass inj = orchestrator.getInstance(InjectedClass.class);
        assertEquals(1, inj.mock);
        inj = orchestrator.getInstance(InjectedClass.class);
        assertEquals(2, inj.mock);
    }

    @Test
    void testInjectedSingletonDefaultConstructor() {
        var orchestrator = new DefaultOrchestrator();
        assertEquals(0, InjectedSingletonClass.mock);
        orchestrator.getInstance(InjectedSingletonClass.class);
        assertEquals(1, InjectedSingletonClass.mock);
        orchestrator.getInstance(InjectedSingletonClass.class);
        assertEquals(1, InjectedSingletonClass.mock);
    }

    static class ConstructorWithParams {
        int inject;

        @Inject
        public ConstructorWithParams(Integer inject) {
            this.inject = inject;
        }
    }

    @Test
    void testConstructorWithInject() {
        var orchestrator = new BasicOrchestrator();

        assertEquals(0, orchestrator.total);
        var res = orchestrator.getInstance(ConstructorWithParams.class);
        assertEquals(1, res.inject);
        assertEquals(1, orchestrator.total);
    }

    static class ConstructorWithNamedParams {
        int i1, i2;

        @Inject
        public ConstructorWithNamedParams(@Named("the fake fake") Integer i1, @Named("the real real") Integer i2) {
            this.i1 = i1;
            this.i2 = i2;
        }
    }

    @Test
    void testConstructorWithNamedInject() {
        var orchestrator = new NamedOrchestrator();

        var res = orchestrator.getInstance(ConstructorWithNamedParams.class);
        assertEquals(2, res.i1);
        assertEquals(1, res.i2);
    }

    static class ProviderWithParamsOrchestrator extends Orchestrator {

        @Provides
        @Named("good string")
        public String provideGoodString() {
            return "whats up man";
        }

        @Provides
        public String provideBadString() {
            return "im evil";
        }

        @Provides
        @Named("result")
        public String provideResult(@Named("good string") String input) {
            return input + " -- not much";
        }

        @Override
        public void configure() {}
    }

    @Test
    void testProviderWithNamedParams() {
        var orchestrator = new ProviderWithParamsOrchestrator();

        var res = orchestrator.getInstance(String.class, "result");
        assertEquals("whats up man -- not much", res);
    }
}
