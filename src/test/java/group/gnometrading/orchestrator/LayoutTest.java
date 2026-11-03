package group.gnometrading.orchestrator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The jar is laid out by process: a session and a shard are separate programs that share only the layers below them,
 * so neither may reach into the other, and the shared layers may reach into neither.
 */
class LayoutTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("group.gnometrading.orchestrator");

    @Test
    void aSessionNeverUsesShardCode() {
        noClasses()
                .that()
                .resideInAPackage("..orchestrator.session..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..orchestrator.shard..")
                .check(CLASSES);
    }

    @Test
    void aShardNeverUsesSessionCode() {
        noClasses()
                .that()
                .resideInAPackage("..orchestrator.shard..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..orchestrator.session..")
                .check(CLASSES);
    }

    @Test
    void theSharedLayersUseNeitherProcess() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "..orchestrator.aeron..",
                        "..orchestrator.runtime..",
                        "..orchestrator.aws..",
                        "..orchestrator.credentials..",
                        "..orchestrator.di..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..orchestrator.session..", "..orchestrator.shard..")
                .check(CLASSES);
    }

    @Test
    void onlyShardsOpenVenueFeeds() {
        classes()
                .that()
                .resideInAPackage("..orchestrator.shard.feeds..")
                .should()
                .onlyBeAccessed()
                .byAnyPackage("..orchestrator.shard..")
                .check(CLASSES);
    }
}
