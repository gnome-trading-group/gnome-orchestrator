package group.gnometrading.collectors;

/** How the shard runtime hands streams to the publisher and opens their publications when sessions lease them. */
interface StreamPublishing {

    /** Nothing published: for a shard run without Aeron (tests). */
    StreamPublishing NONE = new StreamPublishing() {
        @Override
        public void add(final ShardStream stream) {}

        @Override
        public void retire(final ShardStream stream) {}

        @Override
        public StreamPublication open(final int streamId) {
            return null;
        }
    };

    /** From any thread: the publisher starts draining the stream's ring. */
    void add(ShardStream stream);

    /** From any thread: the publisher stops draining it once its market has stopped publishing. */
    void retire(ShardStream stream);

    /** The publication sessions read the stream from; null when nothing publishes. Blocks briefly on the driver. */
    StreamPublication open(int streamId);
}
