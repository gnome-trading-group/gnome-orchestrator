package group.gnometrading.orchestrator.aws;

import group.gnometrading.constants.Stage;
import group.gnometrading.orchestrator.di.Module;
import group.gnometrading.orchestrator.di.Named;
import group.gnometrading.orchestrator.di.Provides;
import group.gnometrading.orchestrator.di.Singleton;
import group.gnometrading.resources.Properties;
import java.io.IOException;

public class PropertiesModule extends Module {

    @Provides
    public final Stage provideStage() {
        return Stage.fromStageName(System.getenv("STAGE"));
    }

    @Provides
    @Singleton
    public final Properties provideProperties(Stage stage, @Named("CLI_ARGS") String[] cliArgs) throws IOException {
        final String path = "orchestrator.%s.properties".formatted(stage.getStageName());
        return new Properties(path, cliArgs);
    }
}
