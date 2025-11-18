package group.gnometrading.shared;

import group.gnometrading.constants.Stage;
import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.resources.Properties;

import java.io.IOException;
import java.util.Arrays;

public interface PropertiesModule {

    @Provides
    default Stage provideStage() {
        return Stage.fromStageName(System.getenv("STAGE"));
    }

    @Provides
    @Singleton
    default Properties provideProperties(
            Stage stage,
            @Named("CLI_ARGS") String[] cliArgs
    ) throws IOException {
        final String path = "orchestrator.%s.properties".formatted(stage.getStageName());
        System.out.println("Loading properties from " + path + " and args: " + Arrays.toString(cliArgs));
        return new Properties(path, cliArgs);
    }

}
