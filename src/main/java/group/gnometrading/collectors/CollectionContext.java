package group.gnometrading.collectors;

/** Identifies the immutable registry snapshot associated with a collection run. */
public record CollectionContext(String collectionId, String contractMetadataKey) {

    static CollectionContext untracked() {
        return new CollectionContext("untracked", "");
    }
}
