package group.gnometrading.orchestrator.gateway.journal;

import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.schemas.Side;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.agrona.IoUtil;

/**
 * Which session owns each order an account has at the venue, kept on local disk so a restarted gateway knows its
 * orders and whose they are. The lane records an order before anything of it is sent ({@link AccountLane.Ownership}),
 * its venue id once known, and its end. The page cache outlives the process, so a crash loses nothing recorded.
 *
 * <p>One memory-mapped file per account. Each record is its length, a CRC32 of the rest, and the rest: kind, session
 * slot and counter, then for an ADD the order's terms, correlation id and venue id. A reader stops at the first record
 * that is short or fails its check: a write torn by a crash counts as never made, so one bad append can't become a
 * crash loop. When the file fills, it is rewritten with only the orders still held.
 *
 * <p>The lane's thread; appending allocates nothing. Rewriting a full file maps a new one and blocks for that while.
 */
public final class OwnershipLog implements AccountLane.Ownership, AutoCloseable {

    private static final long MAGIC = 0x474E4F4D4F574E31L;
    private static final int HEADER = Long.BYTES;
    private static final byte ADD = 1;
    private static final byte VENUE_ID = 2;
    private static final byte END = 3;
    // Length and check before each record.
    private static final int FRAME = Integer.BYTES + Integer.BYTES;
    private static final int KEY = 1 + Long.BYTES + Long.BYTES;
    private static final int MAX_RECORD = KEY + 128 + 2 * (1 + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);

    private final Path path;
    private final long size;
    private final CRC32 crc = new CRC32();
    // The orders the lane holds, for rewriting a full log: its forEachHeld.
    private Consumer<Consumer<OrderContext>> held;
    private MappedByteBuffer file;

    private OwnershipLog(final Path path, final long size) {
        this.path = path;
        this.size = size;
    }

    /**
     * Reads the orders still open in the log at {@code path} (none if there is no file), then starts it afresh holding
     * just those.
     *
     * @param recovered given each open order as recorded; valid only during the call
     */
    public static OwnershipLog open(final Path path, final long size, final Consumer<OrderContext> recovered)
            throws IOException {
        final List<OrderContext> open = read(path);
        final OwnershipLog log = new OwnershipLog(path, size);
        log.file = log.create(open);
        for (final OrderContext ctx : open) {
            recovered.accept(ctx);
        }
        return log;
    }

    /** Where a full log is rewritten from: the lane's held orders ({@link AccountLane#forEachHeld}). */
    public void held(final Consumer<Consumer<OrderContext>> held) {
        this.held = held;
    }

    @Override
    public void added(final OrderContext ctx) {
        final MappedByteBuffer out = room();
        final int start = begin(out, ADD, ctx);
        writeAdd(out, ctx);
        end(out, start);
    }

    @Override
    public void venueIdLearned(final OrderContext ctx) {
        final MappedByteBuffer out = room();
        final int start = begin(out, VENUE_ID, ctx);
        writeBytes(out, ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
        end(out, start);
    }

    @Override
    public void ended(final OrderContext ctx) {
        final MappedByteBuffer out = room();
        final int start = begin(out, END, ctx);
        end(out, start);
    }

    @Override
    public void close() {
        IoUtil.unmap(this.file);
    }

    // ----- writing -----

    /** The file, rewritten with only the held orders first if the next record might not fit. */
    private MappedByteBuffer room() {
        if (this.file.remaining() < FRAME + MAX_RECORD) {
            final List<OrderContext> held = new ArrayList<>();
            this.held.accept(ctx -> {
                final OrderContext copy = new OrderContext();
                copy.copyFrom(ctx);
                held.add(copy);
            });
            try {
                final MappedByteBuffer fresh = create(held);
                IoUtil.unmap(this.file);
                this.file = fresh;
            } catch (IOException e) {
                throw new UncheckedIOException("Can't rewrite the ownership log " + this.path, e);
            }
        }
        return this.file;
    }

    private static int begin(final MappedByteBuffer out, final byte kind, final OrderContext ctx) {
        final int start = out.position();
        out.position(start + FRAME);
        out.put(kind);
        out.putLong(ctx.sessionSlot);
        out.putLong(ctx.clientOidCounter);
        return start;
    }

    private static void writeAdd(final MappedByteBuffer out, final OrderContext ctx) {
        out.putInt(ctx.clientOidStrategyId);
        out.putInt(ctx.exchangeId);
        out.putLong(ctx.securityId);
        out.put(ctx.side == null ? 0 : (byte) ctx.side.value());
        out.putShort(ctx.flags);
        out.putLong(ctx.originalQty);
        out.putLong(ctx.limitPrice);
        out.putLong(ctx.submittedAtMillis);
        out.put((byte) (ctx.awaitsFinalFill ? 1 : 0));
        writeBytes(out, ctx.correlationIdBytes, ctx.correlationIdLength);
        writeBytes(out, ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
    }

    private static void writeBytes(final MappedByteBuffer out, final byte[] bytes, final int length) {
        out.put((byte) length);
        out.put(bytes, 0, length);
    }

    /** Fills in the record's length and check; written last, so a record is whole once its length is. */
    private void end(final MappedByteBuffer out, final int start) {
        final int bodyStart = start + FRAME;
        final int length = out.position() - bodyStart;
        this.crc.reset();
        final int limit = out.limit();
        out.position(bodyStart).limit(bodyStart + length);
        this.crc.update(out);
        out.limit(limit);
        out.putInt(start + Integer.BYTES, (int) this.crc.getValue());
        out.putInt(start, length);
        out.position(bodyStart + length);
    }

    /** A new file holding {@code open}, swapped in whole by a rename, so a crash midway leaves the old one. */
    private MappedByteBuffer create(final List<OrderContext> open) throws IOException {
        Files.createDirectories(this.path.getParent());
        final Path next = this.path.resolveSibling(this.path.getFileName() + ".next");
        final MappedByteBuffer out;
        try (FileChannel channel =
                FileChannel.open(next, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.truncate(0);
            channel.truncate(this.size);
            out = channel.map(FileChannel.MapMode.READ_WRITE, 0, this.size);
        }
        out.order(ByteOrder.LITTLE_ENDIAN);
        out.putLong(MAGIC);
        for (final OrderContext ctx : open) {
            final int start = begin(out, ADD, ctx);
            writeAdd(out, ctx);
            end(out, start);
        }
        out.force();
        Files.move(next, this.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    // ----- reading -----

    /** The orders the log holds open, in the order they were added; reads up to the first torn or bad record. */
    static List<OrderContext> read(final Path path) throws IOException {
        final Map<String, OrderContext> open = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        final MappedByteBuffer in;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            in = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }
        try {
            in.order(ByteOrder.LITTLE_ENDIAN);
            if (in.remaining() < HEADER || in.getLong() != MAGIC) {
                return new ArrayList<>();
            }
            final CRC32 crc = new CRC32();
            while (in.remaining() >= FRAME) {
                final int length = in.getInt();
                final int check = in.getInt();
                if (length < KEY || length > MAX_RECORD || length > in.remaining()) {
                    break;
                }
                final int bodyStart = in.position();
                crc.reset();
                crc.update(in.duplicate().position(bodyStart).limit(bodyStart + length));
                if ((int) crc.getValue() != check) {
                    break;
                }
                apply(in, open);
                in.position(bodyStart + length);
            }
        } finally {
            IoUtil.unmap(in);
        }
        return new ArrayList<>(open.values());
    }

    private static void apply(final MappedByteBuffer in, final Map<String, OrderContext> open) {
        final byte kind = in.get();
        final long slot = in.getLong();
        final long counter = in.getLong();
        final String key = slot + ":" + counter;
        switch (kind) {
            case ADD -> open.put(key, readAdd(in, slot, counter));
            case VENUE_ID -> {
                final OrderContext ctx = open.get(key);
                if (ctx != null) {
                    ctx.exchangeOrderIdLength = readBytes(in, ctx.exchangeOrderIdBytes);
                }
            }
            case END -> open.remove(key);
            default -> {}
        }
    }

    private static OrderContext readAdd(final MappedByteBuffer in, final long slot, final long counter) {
        final OrderContext ctx = new OrderContext();
        ctx.sessionSlot = slot;
        ctx.clientOidCounter = counter;
        ctx.clientOidStrategyId = in.getInt();
        ctx.exchangeId = in.getInt();
        ctx.securityId = in.getLong();
        final byte side = in.get();
        ctx.side = side == 0 ? null : Side.get(side);
        ctx.flags = in.getShort();
        ctx.originalQty = in.getLong();
        ctx.leavesQty = ctx.originalQty;
        ctx.limitPrice = in.getLong();
        ctx.submittedAtMillis = in.getLong();
        ctx.awaitsFinalFill = in.get() == 1;
        ctx.correlationIdLength = readBytes(in, ctx.correlationIdBytes);
        ctx.exchangeOrderIdLength = readBytes(in, ctx.exchangeOrderIdBytes);
        return ctx;
    }

    private static int readBytes(final MappedByteBuffer in, final byte[] into) {
        final int length = in.get() & 0xFF;
        in.get(into, 0, length);
        return length;
    }
}
