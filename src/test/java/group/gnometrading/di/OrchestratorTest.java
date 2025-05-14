package group.gnometrading.di;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorTest {

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