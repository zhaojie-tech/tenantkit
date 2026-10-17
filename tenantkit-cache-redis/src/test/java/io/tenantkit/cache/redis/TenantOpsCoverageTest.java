package io.tenantkit.cache.redis;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.tenantkit.cache.redis.properties.TenantKitRedisProperties;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantMissingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.geo.Point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4 backlog #6 closure: every ops family's key positions are rewritten.
 * Declared-key coverage only — values and fields must pass through untouched.
 */
class TenantOpsCoverageTest {

    private static final TenantId ALPHA = TenantId.of("alpha");

    private final RedisTemplate<String, Object> delegate = mock(RedisTemplate.class);
    private final TenantRedisTemplate template = new TenantRedisTemplate(delegate, new TenantKitRedisProperties());

    @AfterEach
    void cleanContext() {
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void hashKeysArePrefixedAndValuesUntouched() {
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> delegateOps = mock(HashOperations.class);
        when(delegate.opsForHash()).thenReturn(delegateOps);
        HashOperations<String, Object, Object> ops = template.opsForHash();

        TenantContext.runAs(ALPHA, () -> ops.put("cart:1", "item", "value"));
        verify(delegateOps).put("t:alpha:cart:1", "item", "value");

        TenantContext.runAs(ALPHA, () -> ops.get("cart:1", "item"));
        verify(delegateOps).get("t:alpha:cart:1", "item");
    }

    @Test
    void listTwoKeyMethodRewritesBothKeys() {
        @SuppressWarnings("unchecked")
        ListOperations<String, Object> delegateOps = mock(ListOperations.class);
        when(delegate.opsForList()).thenReturn(delegateOps);
        ListOperations<String, Object> ops = template.opsForList();

        TenantContext.runAs(ALPHA, () -> ops.rightPopAndLeftPush("queue:a", "queue:b"));
        verify(delegateOps).rightPopAndLeftPush("t:alpha:queue:a", "t:alpha:queue:b");

        TenantContext.runAs(ALPHA, () -> ops.range("queue:a", 0, 10));
        verify(delegateOps).range("t:alpha:queue:a", 0, 10);
    }

    @Test
    void setMoveAndStoreFamilyRewriteEveryKeyPosition() {
        @SuppressWarnings("unchecked")
        SetOperations<String, Object> delegateOps = mock(SetOperations.class);
        when(delegate.opsForSet()).thenReturn(delegateOps);
        SetOperations<String, Object> ops = template.opsForSet();

        TenantContext.runAs(ALPHA, () -> ops.move("src", "member", "dst"));
        verify(delegateOps).move("t:alpha:src", "member", "t:alpha:dst");

        TenantContext.runAs(ALPHA, () -> ops.intersectAndStore("a", "b", "dest"));
        verify(delegateOps).intersectAndStore("t:alpha:a", "t:alpha:b", "t:alpha:dest");

        TenantContext.runAs(ALPHA, () -> ops.intersect("a", List.of("b", "c")));
        verify(delegateOps).intersect("t:alpha:a", List.of("t:alpha:b", "t:alpha:c"));

        TenantContext.runAs(ALPHA, () -> ops.union("a", "b"));
        verify(delegateOps).union("t:alpha:a", "t:alpha:b");
    }

    @Test
    void zsetStoreFamilyRewritesEveryKeyPosition() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, Object> delegateOps = mock(ZSetOperations.class);
        when(delegate.opsForZSet()).thenReturn(delegateOps);
        ZSetOperations<String, Object> ops = template.opsForZSet();

        TenantContext.runAs(ALPHA, () -> ops.unionAndStore("low", "high", "combined"));
        verify(delegateOps).unionAndStore("t:alpha:low", "t:alpha:high", "t:alpha:combined");

        TenantContext.runAs(ALPHA, () -> ops.add("board", "member", 1.0));
        verify(delegateOps).add("t:alpha:board", "member", 1.0);
    }

    @Test
    void geoAndHyperLogLogKeysArePrefixed() {
        @SuppressWarnings("unchecked")
        GeoOperations<String, Object> geoOps = mock(GeoOperations.class);
        when(delegate.opsForGeo()).thenReturn(geoOps);
        TenantContext.runAs(ALPHA, () -> template.opsForGeo().add("cities", new Point(1, 2), "berlin"));
        verify(geoOps).add("t:alpha:cities", new Point(1, 2), "berlin");

        @SuppressWarnings("unchecked")
        HyperLogLogOperations<String, Object> hllOps = mock(HyperLogLogOperations.class);
        when(delegate.opsForHyperLogLog()).thenReturn(hllOps);
        TenantContext.runAs(ALPHA, () -> template.opsForHyperLogLog().union("target", "a", "b"));
        verify(hllOps).union("t:alpha:target", "t:alpha:a", "t:alpha:b");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void streamReadOffsetsAndReturnedRecordsAreRebased() throws Exception {
        StreamOperations<String, Object, Object> delegateOps = mock(StreamOperations.class);
        when(delegate.opsForStream()).thenReturn(delegateOps);
        StreamOperations<String, Object, Object> ops = template.opsForStream();

        MapRecord<String, Object, Object> record = MapRecord.create("t:alpha:events", Map.of("k", "v"));
        when(delegateOps.read(any(StreamOffset.class)))
                .thenReturn(List.of(record));

        List<MapRecord<String, Object, Object>> result = TenantContext.callAs(ALPHA,
                () -> ops.read(StreamOffset.fromStart("events")));

        // request side: the offset key was prefixed
        org.mockito.ArgumentCaptor<StreamOffset> captor = org.mockito.ArgumentCaptor.forClass(StreamOffset.class);
        verify(delegateOps).read(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo("t:alpha:events");
        // response side: the returned record's stream key is logical again
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStream()).isEqualTo("events");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void streamRangeAndAddRewriteKeys() {
        StreamOperations<String, Object, Object> delegateOps = mock(StreamOperations.class);
        when(delegate.opsForStream()).thenReturn(delegateOps);
        StreamOperations<String, Object, Object> ops = template.opsForStream();

        TenantContext.runAs(ALPHA, () -> ops.add("events", Map.of("k", "v")));
        verify(delegateOps).add(eq("t:alpha:events"), any(Map.class));

        TenantContext.runAs(ALPHA, () -> ops.size("events"));
        verify(delegateOps).size("t:alpha:events");

        TenantContext.runAs(ALPHA, () -> ops.range("events",
                org.springframework.data.domain.Range.closed("0", "9")));
        verify(delegateOps).range(eq("t:alpha:events"), any(org.springframework.data.domain.Range.class));

        TenantContext.runAs(ALPHA, () -> ops.createGroup("events", "workers"));
        verify(delegateOps).createGroup("t:alpha:events", "workers");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void streamRecordOverloadsAreReKeyedOnTheWayIn() {
        StreamOperations<String, Object, Object> delegateOps = mock(StreamOperations.class);
        when(delegate.opsForStream()).thenReturn(delegateOps);
        StreamOperations<String, Object, Object> ops = template.opsForStream();
        MapRecord<String, Object, Object> record = MapRecord.create("events",
                        Map.<Object, Object>of("k", "v"))
                .withId(org.springframework.data.redis.connection.stream.RecordId.of("1-0"));

        TenantContext.runAs(ALPHA, () -> ops.add(record));
        org.mockito.ArgumentCaptor<Object> addCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(delegateOps).add((MapRecord) addCaptor.capture());
        assertThat(((MapRecord) addCaptor.getValue()).getStream()).isEqualTo("t:alpha:events");

        TenantContext.runAs(ALPHA, () -> ops.delete(record));
        org.mockito.ArgumentCaptor<Object> deleteCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(delegateOps).delete((org.springframework.data.redis.connection.stream.Record) deleteCaptor.capture());
        assertThat(((org.springframework.data.redis.connection.stream.Record) deleteCaptor.getValue()).getStream())
                .isEqualTo("t:alpha:events");

        TenantContext.runAs(ALPHA, () -> ops.acknowledge("workers", record));
        org.mockito.ArgumentCaptor<Object> ackCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(delegateOps).acknowledge(eq("workers"), (org.springframework.data.redis.connection.stream.Record) ackCaptor.capture());
        assertThat(((org.springframework.data.redis.connection.stream.Record) ackCaptor.getValue()).getStream())
                .isEqualTo("t:alpha:events");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void streamRangeWithClassArgAndConsumerRead() {
        StreamOperations<String, Object, Object> delegateOps = mock(StreamOperations.class);
        when(delegate.opsForStream()).thenReturn(delegateOps);
        StreamOperations<String, Object, Object> ops = template.opsForStream();

        TenantContext.runAs(ALPHA, () -> ops.range(String.class, "events",
                org.springframework.data.domain.Range.closed("0", "9")));
        verify(delegateOps).range(eq(String.class), eq("t:alpha:events"),
                any(org.springframework.data.domain.Range.class));

        TenantContext.runAs(ALPHA, () -> ops.read(
                org.springframework.data.redis.connection.stream.Consumer.from("group", "consumer"),
                StreamOffset.fromStart("events")));
        org.mockito.ArgumentCaptor<StreamOffset[]> offsets =
                org.mockito.ArgumentCaptor.forClass(StreamOffset[].class);
        verify(delegateOps).read(any(org.springframework.data.redis.connection.stream.Consumer.class),
                offsets.capture());
        assertThat(offsets.getValue()).hasSize(1);
        assertThat(offsets.getValue()[0].getKey()).isEqualTo("t:alpha:events");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void objectRecordResultsAreReKeyedBack() throws Exception {
        StreamOperations<String, Object, Object> delegateOps = mock(StreamOperations.class);
        when(delegate.opsForStream()).thenReturn(delegateOps);
        StreamOperations<String, Object, Object> ops = template.opsForStream();

        org.springframework.data.redis.connection.stream.ObjectRecord<String, String> physical =
                org.springframework.data.redis.connection.stream.ObjectRecord.create("t:alpha:events", "payload")
                        .withId(org.springframework.data.redis.connection.stream.RecordId.of("2-0"));
        when(delegateOps.range((Class) org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                (org.springframework.data.domain.Range) org.mockito.ArgumentMatchers.any()))
                .thenReturn((List) List.of(physical));

        List<org.springframework.data.redis.connection.stream.ObjectRecord<String, String>> result =
                TenantContext.callAs(ALPHA, () -> ops.range(String.class, "events",
                        org.springframework.data.domain.Range.closed("0", "9")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStream()).isEqualTo("events");
        assertThat(result.get(0).getValue()).isEqualTo("payload");
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundOpsEncodeTheKeyAtBindingTime() {
        org.springframework.data.redis.core.BoundHashOperations<String, Object, Object> bound = mock(
                org.springframework.data.redis.core.BoundHashOperations.class);
        when(delegate.boundHashOps("cart:1")).thenReturn(bound);
        TenantContext.runAs(ALPHA, () -> template.boundHashOps("cart:1"));
        verify(delegate).boundHashOps("t:alpha:cart:1");

        org.springframework.data.redis.core.BoundValueOperations<String, Object> boundValue = mock(
                org.springframework.data.redis.core.BoundValueOperations.class);
        when(delegate.boundValueOps("flag")).thenReturn(boundValue);
        TenantContext.runAs(ALPHA, () -> template.boundValueOps("flag"));
        verify(delegate).boundValueOps("t:alpha:flag");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedKeysBypassPrefixingInEveryFamily() {
        TenantKitRedisProperties properties = new TenantKitRedisProperties();
        properties.setSharedKeys(List.of("shared:*"));
        RedisTemplate<String, Object> sharedDelegate = mock(RedisTemplate.class);
        TenantRedisTemplate sharedTemplate = new TenantRedisTemplate(sharedDelegate, properties);

        HashOperations<String, Object, Object> delegateOps = mock(HashOperations.class);
        when(sharedDelegate.opsForHash()).thenReturn(delegateOps);

        TenantContext.runAs(ALPHA, () -> sharedTemplate.opsForHash().put("shared:config", "k", "v"));
        verify(delegateOps).put("shared:config", "k", "v");
    }

    @Test
    @SuppressWarnings("unchecked")
    void strictPolicyAppliesToEveryOpsFamily() {
        HashOperations<String, Object, Object> delegateOps = mock(HashOperations.class);
        when(delegate.opsForHash()).thenReturn(delegateOps);

        assertThatThrownBy(() -> template.opsForHash().get("k", "f"))
                .isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(() -> template.opsForSet().add("k", "v"))
                .isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(() -> template.opsForZSet().add("k", "v", 1.0))
                .isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(() -> template.opsForList().size("k"))
                .isInstanceOf(TenantMissingException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void transparentModeDoesNotRewriteAnything() {
        TenantKitRedisProperties properties = new TenantKitRedisProperties();
        properties.setKeyPrefix(false);
        RedisTemplate<String, Object> raw = mock(RedisTemplate.class);
        TenantRedisTemplate transparent = new TenantRedisTemplate(raw, properties);

        ZSetOperations<String, Object> delegateOps = mock(ZSetOperations.class);
        when(raw.opsForZSet()).thenReturn(delegateOps);

        // no tenant bound at all: transparent mode performs no policy check
        transparent.opsForZSet().add("plain", "member", 1.0);
        verify(delegateOps).add("plain", "member", 1.0);
    }

    @Test
    void unsupportedRawSurfacesNameTheSafetyBoundary() {
        assertThatThrownBy(() -> template.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("by design");
        assertThatThrownBy(() -> template.opsForCluster())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("by design");
    }
}
