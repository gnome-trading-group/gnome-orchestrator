package group.gnometrading.shared;

import group.gnometrading.constants.Stage;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.resources.Properties;

import java.io.IOException;

public interface PropertiesModule {

    @Provides
    default Stage provideStage() {
        return Stage.fromStageName(System.getenv("STAGE"));
    }

    @Provides
    @Singleton
    default Properties provideProperties(Stage stage) throws IOException {
        final String path = "orchestrator.%s.properties".formatted(stage.getStageName());
        return new Properties(path);
    }

}
