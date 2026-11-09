package group.gnometrading.orchestrator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The jar is laid out by process: a session, a shard and an order gateway are separate programs that share only the
 * layers below them, so none may reach into another, and the shared layers may reach into none.
 */
class LayoutTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("group.gnometrading.orchestrator");

    @Test
    void aSessionUsesNeitherShardNorGatewayCode() {
        noClasses()
                .that()
                .resideInAPackage("..orchestrator.session..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..orchestrator.shard..", "..orchestrator.gateway..")
                .check(CLASSES);
    }

    @Test
    void aShardUsesNeitherSessionNorGatewayCode() {
        noClasses()
                .that()
                .resideInAPackage("..orchestrator.shard..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..orchestrator.session..", "..orchestrator.gateway..")
                .check(CLASSES);
    }

    @Test
    void aGatewayUsesNeitherSessionNorShardCode() {
        noClasses()
                .that()
                .resideInAPackage("..orchestrator.gateway..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..orchestrator.session..", "..orchestrator.shard..")
                .check(CLASSES);
    }

    @Test
    void theGatewaysSharedCoreUsesNoneOfItsParts() {
        noClasses()
                .that()
                .resideInAPackage("..orchestrator.gateway.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..orchestrator.gateway",
                        "..orchestrator.gateway.accounts..",
                        "..orchestrator.gateway.transport..",
                        "..orchestrator.gateway.control..",
                        "..orchestrator.gateway.journal..")
                .check(CLASSES);
    }

    @Test
    void eachPartOfTheGatewayUsesOnlyItsCore() {
        final String[] parts = {
            "..orchestrator.gateway.accounts..",
            "..orchestrator.gateway.transport..",
            "..orchestrator.gateway.control..",
            "..orchestrator.gateway.journal.."
        };
        for (final String part : parts) {
            final String[] others =
                    Arrays.stream(parts).filter(other -> !other.equals(part)).toArray(String[]::new);
            noClasses()
                    .that()
                    .resideInAPackage(part)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(others)
                    .orShould()
                    .dependOnClassesThat()
                    .resideInAPackage("..orchestrator.gateway")
                    .check(CLASSES);
        }
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
                .resideInAnyPackage("..orchestrator.session..", "..orchestrator.shard..", "..orchestrator.gateway..")
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
