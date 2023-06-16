package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ConsumedThroughput;
import datadog.trace.bootstrap.instrumentation.api.FanOutThroughput;
import datadog.trace.bootstrap.instrumentation.api.GeneratedThroughput;
import datadog.trace.bootstrap.instrumentation.api.InboxItem;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.ProducedThroughput;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.bootstrap.instrumentation.api.TerminatedThroughput;
import datadog.trace.util.FNV64Hash;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultPathwayContext implements PathwayContext {
  private static final Logger log = LoggerFactory.getLogger(DefaultPathwayContext.class);
  private final Lock lock = new ReentrantLock();
  private final WellKnownTags wellKnownTags;
  private final TimeSource timeSource;
  private final GrowingByteArrayOutput outputBuffer =
      GrowingByteArrayOutput.withInitialCapacity(20);

  // pathwayStartNanos is nanoseconds since epoch
  // Nano ticks is necessary because time differences should use a monotonically increasing clock
  // ticks is not comparable across JVMs
  private final long pathwayStartNanos;
  private final long pathwayStartNanoTicks;
  private final long edgeStartNanoTicks;
  private final long hash;
  private final boolean started;

  private AtomicInteger numChildren;

  private static final Set<String> hashableTagKeys =
      new HashSet<String>(
          Arrays.asList(
              TagsProcessor.GROUP_TAG,
              TagsProcessor.TYPE_TAG,
              TagsProcessor.DIRECTION_TAG,
              TagsProcessor.TOPIC_TAG,
              TagsProcessor.EXCHANGE_TAG));

  public DefaultPathwayContext(TimeSource timeSource, WellKnownTags wellKnownTags) {
    this.timeSource = timeSource;
    this.wellKnownTags = wellKnownTags;
    this.pathwayStartNanos = 0L;
    this.pathwayStartNanoTicks = 0L;
    this.edgeStartNanoTicks = 0L;
    this.hash = 0L;
    this.started = false;
    this.numChildren = new AtomicInteger(0);
  }

  private DefaultPathwayContext(
      TimeSource timeSource,
      WellKnownTags wellKnownTags,
      long pathwayStartNanos,
      long pathwayStartNanoTicks,
      long edgeStartNanoTicks,
      long hash) {
    this.timeSource = timeSource;
    this.wellKnownTags = wellKnownTags;
    this.pathwayStartNanos = pathwayStartNanos;
    this.pathwayStartNanoTicks = pathwayStartNanoTicks;
    this.edgeStartNanoTicks = edgeStartNanoTicks;
    this.hash = hash;
    this.started = true;
    this.numChildren = new AtomicInteger(0);
  }

  @Override
  public boolean isStarted() {
    return started;
  }

  @Override
  public long getHash() {
    return hash;
  }

  @Override
  public PathwayContext createNew(
      LinkedHashMap<String, String> sortedTags, Consumer<InboxItem> inboxItemConsumer) {
    long startNanos = timeSource.getCurrentTimeNanos();
    long nanoTicks = timeSource.getNanoTicks();
      // So far, each tag key has only one tag value, so we're initializing the capacity to match
      // the number of tag keys for now. We should revisit this later if it's no longer the case.
      List<String> allTags = new ArrayList<>(sortedTags.size());
      PathwayHashBuilder pathwayHashBuilder = new PathwayHashBuilder(wellKnownTags);

      long parentPathwayStartNanos;
      long parentPathwayStartNanoTicks;
      long parentEdgeStartNanoTicks;
      if (!started) {
        parentPathwayStartNanos = startNanos;
        parentPathwayStartNanoTicks = nanoTicks;
        parentEdgeStartNanoTicks = nanoTicks;
      } else {
        parentPathwayStartNanos = pathwayStartNanos;
        parentPathwayStartNanoTicks = pathwayStartNanoTicks;
        parentEdgeStartNanoTicks = edgeStartNanoTicks;
      }

      for (Map.Entry<String, String> entry : sortedTags.entrySet()) {
        String tag = TagsProcessor.createTag(entry.getKey(), entry.getValue());
        if (tag == null) {
          continue;
        }
        if (hashableTagKeys.contains(entry.getKey())) {
          pathwayHashBuilder.addTag(tag);
        }
        allTags.add(tag);
      }

      long nodeHash = generateNodeHash(pathwayHashBuilder);

      long newHash = generatePathwayHash(nodeHash, hash);

      long pathwayLatencyNano = nanoTicks - parentPathwayStartNanoTicks;
      long edgeLatencyNano = nanoTicks - parentEdgeStartNanoTicks;

      StatsPoint point =
          new StatsPoint(
              allTags,
              newHash,
              hash,
              timeSource.getCurrentTimeNanos(),
              pathwayLatencyNano,
              edgeLatencyNano);
      long childEdgeStartNanoTicks = nanoTicks;

      inboxItemConsumer.accept(point);
      log.debug("Checkpoint set {}, hash source: {}", point, pathwayHashBuilder);
      PathwayContext newPathwayContext = new DefaultPathwayContext(
          timeSource, wellKnownTags,
          parentPathwayStartNanos,
          parentPathwayStartNanoTicks,
          childEdgeStartNanoTicks,
          newHash);
    log.debug("Created new pathway context {}", newPathwayContext);

    int numTotalChildren = numChildren.incrementAndGet();
    if (numTotalChildren > 1) {
      // If it's equal to 1, this pathway is the only child, so no fanOut detected.
      // Otherwise, there is fanOut.
      FanOutThroughput fanOutThroughput = new FanOutThroughput(parentPathwayStartNanos);
      inboxItemConsumer.accept(fanOutThroughput);
    }

    if (hash == 0L) {
      GeneratedThroughput generatedThroughput = new GeneratedThroughput(parentPathwayStartNanos);
      inboxItemConsumer.accept(generatedThroughput);
    }

    if (sortedTags.containsKey("direction")) {
      String direction = sortedTags.get("direction");
      if ("in".equals(direction)) {
        ConsumedThroughput consumedThroughput = new ConsumedThroughput(parentPathwayStartNanos);
        inboxItemConsumer.accept(consumedThroughput);
      } else {
        ProducedThroughput producedThroughput = new ProducedThroughput(parentPathwayStartNanos);
        inboxItemConsumer.accept(producedThroughput);
      }
    }

    return newPathwayContext;
  }

  @Override
  public void terminate(Consumer<InboxItem> inboxItemConsumer) {
    if (!started) {
      // Nothing to terminated since this pathway hasn't even been started yet.
      log.error("[terminate] Cannot terminate");
      return;
    }
    log.error("[terminate] terminate");
    TerminatedThroughput terminatedThroughput = new TerminatedThroughput(pathwayStartNanos);
    inboxItemConsumer.accept(terminatedThroughput);
  }

  @Override
  public byte[] encode() throws IOException {
    lock.lock();
    try {
      if (!started) {
        throw new IllegalStateException("Context must be started to encode");
      }

      outputBuffer.clear();
      outputBuffer.writeLongLE(hash);

      long pathwayStartMillis = TimeUnit.NANOSECONDS.toMillis(pathwayStartNanos);
      VarEncodingHelper.encodeSignedVarLong(outputBuffer, pathwayStartMillis);

      long edgeStartMillis =
          pathwayStartMillis
              + TimeUnit.NANOSECONDS.toMillis(edgeStartNanoTicks - pathwayStartNanoTicks);

      VarEncodingHelper.encodeSignedVarLong(outputBuffer, edgeStartMillis);
      return Base64.getEncoder().encode(outputBuffer.trimmedCopy());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String strEncode() throws IOException {
    byte[] bytes = encode();
    if (bytes == null) {
      return null;
    }
    return new String(bytes, ISO_8859_1);
  }

  @Override
  public String toString() {
    lock.lock();
    try {
      if (started) {
        return "PathwayContext[ Hash "
            + Long.toUnsignedString(hash)
            + ", Start: "
            + pathwayStartNanos
            + ", StartTicks: "
            + pathwayStartNanoTicks
            + ", Edge Start Ticks: "
            + edgeStartNanoTicks
            + ", objectHashcode:"
            + hashCode()
            + "]";
      } else {
        return "PathwayContext [Not Started]";
      }
    } finally {
      lock.unlock();
    }
  }

  private static class PathwayContextExtractor implements AgentPropagation.KeyClassifier {
    private final TimeSource timeSource;
    private final WellKnownTags wellKnownTags;
    private DefaultPathwayContext extractedContext;

    PathwayContextExtractor(TimeSource timeSource, WellKnownTags wellKnownTags) {
      this.timeSource = timeSource;
      this.wellKnownTags = wellKnownTags;
    }

    @Override
    public boolean accept(String key, String value) {
      if (PathwayContext.PROPAGATION_KEY_BASE64.equalsIgnoreCase(key)) {
        try {
          extractedContext = strDecode(timeSource, wellKnownTags, value);
        } catch (IOException e) {
          return false;
        }
      }
      return true;
    }
  }

  private static class BinaryPathwayContextExtractor
      implements AgentPropagation.BinaryKeyClassifier {
    private final TimeSource timeSource;
    private final WellKnownTags wellKnownTags;
    private DefaultPathwayContext extractedContext;

    BinaryPathwayContextExtractor(TimeSource timeSource, WellKnownTags wellKnownTags) {
      this.timeSource = timeSource;
      this.wellKnownTags = wellKnownTags;
    }

    @Override
    public boolean accept(String key, byte[] value) {
      // older versions support, should be removed in the future
      if (PathwayContext.PROPAGATION_KEY.equalsIgnoreCase(key)) {
        try {
          extractedContext = decode(timeSource, wellKnownTags, value);
        } catch (IOException e) {
          return false;
        }
      }

      if (PathwayContext.PROPAGATION_KEY_BASE64.equalsIgnoreCase(key)) {
        try {
          extractedContext = base64Decode(timeSource, wellKnownTags, value);
        } catch (IOException e) {
          return false;
        }
      }
      return true;
    }
  }

  static <C> DefaultPathwayContext extract(
      C carrier,
      AgentPropagation.ContextVisitor<C> getter,
      TimeSource timeSource,
      WellKnownTags wellKnownTags) {
    if (getter instanceof AgentPropagation.BinaryContextVisitor) {
      return extractBinary(
          carrier, (AgentPropagation.BinaryContextVisitor) getter, timeSource, wellKnownTags);
    }
    PathwayContextExtractor pathwayContextExtractor =
        new PathwayContextExtractor(timeSource, wellKnownTags);
    getter.forEachKey(carrier, pathwayContextExtractor);
    if (pathwayContextExtractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);
    }
    return pathwayContextExtractor.extractedContext;
  }

  static <C> DefaultPathwayContext extractBinary(
      C carrier,
      AgentPropagation.BinaryContextVisitor<C> getter,
      TimeSource timeSource,
      WellKnownTags wellKnownTags) {
    BinaryPathwayContextExtractor pathwayContextExtractor =
        new BinaryPathwayContextExtractor(timeSource, wellKnownTags);
    getter.forEachKey(carrier, pathwayContextExtractor);
    if (pathwayContextExtractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);
    }
    return pathwayContextExtractor.extractedContext;
  }

  private static DefaultPathwayContext strDecode(
      TimeSource timeSource, WellKnownTags wellKnownTags, String data) throws IOException {
    byte[] base64Bytes = data.getBytes(UTF_8);
    return base64Decode(timeSource, wellKnownTags, base64Bytes);
  }

  private static DefaultPathwayContext base64Decode(
      TimeSource timeSource, WellKnownTags wellKnownTags, byte[] data) throws IOException {
    return decode(timeSource, wellKnownTags, Base64.getDecoder().decode(data));
  }

  private static DefaultPathwayContext decode(
      TimeSource timeSource, WellKnownTags wellKnownTags, byte[] data) throws IOException {
    ByteArrayInput input = ByteArrayInput.wrap(data);

    long hash = input.readLongLE();

    long pathwayStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long pathwayStartNanos = TimeUnit.MILLISECONDS.toNanos(pathwayStartMillis);

    // Convert the start time to the current JVM's nanoclock
    long nowNanos = timeSource.getCurrentTimeNanos();
    long nanosSinceStart = nowNanos - pathwayStartNanos;
    long nowNanoTicks = timeSource.getNanoTicks();
    long pathwayStartNanoTicks = nowNanoTicks - nanosSinceStart;

    long edgeStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long edgeStartNanoTicks =
        pathwayStartNanoTicks + TimeUnit.MILLISECONDS.toNanos(edgeStartMillis - pathwayStartMillis);

    return new DefaultPathwayContext(
        timeSource,
        wellKnownTags,
        pathwayStartNanos,
        pathwayStartNanoTicks,
        edgeStartNanoTicks,
        hash);
  }

  private static class PathwayHashBuilder {
    private final StringBuilder builder;

    public PathwayHashBuilder(WellKnownTags wellKnownTags) {
      builder = new StringBuilder();
      builder.append(wellKnownTags.getService());
      builder.append(wellKnownTags.getEnv());

      String primaryTag = Config.get().getPrimaryTag();
      if (primaryTag != null) {
        builder.append(primaryTag);
      }
    }

    public void addTag(String tag) {
      builder.append(tag);
    }

    public long generateHash() {
      return FNV64Hash.generateHash(builder.toString(), FNV64Hash.Version.v1);
    }

    @Override
    public String toString() {
      return builder.toString();
    }
  }

  private long generateNodeHash(PathwayHashBuilder pathwayHashBuilder) {
    return pathwayHashBuilder.generateHash();
  }

  private long generatePathwayHash(long nodeHash, long parentHash) {
    lock.lock();
    try {
      outputBuffer.clear();
      outputBuffer.writeLongLE(nodeHash);
      outputBuffer.writeLongLE(parentHash);

      return FNV64Hash.generateHash(outputBuffer.backingArray(), 0, 16, FNV64Hash.Version.v1);
    } finally {
      lock.unlock();
    }
  }
}
