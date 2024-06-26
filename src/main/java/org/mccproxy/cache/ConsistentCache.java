package org.mccproxy.cache;

import com.google.common.annotations.VisibleForTesting;
import org.mccproxy.ml.ObsoleteItemsPredictor;
import org.mccproxy.ml.SimpleObsoleteItemsPredictor;
import org.mccproxy.proxy.ItemRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static java.lang.Long.min;
import static org.apache.commons.lang3.ObjectUtils.max;

public class ConsistentCache {
    private static Logger logger =
            LoggerFactory.getLogger(ConsistentCache.class.getName());
    private HashMap<String, ItemNode> cachedItems;
    private ItemNode dummyHead, dummyTail;
    private int currentSize;
    private int maxSize;
    private int itemCount;
    private ObsoleteItemsPredictor obsoleteItemsPredictor;

    // to divide the run into phases
    //    An item in C is marked at the time when it is brought into C, or
    //    when it is part of an MCC hit for. A phase ends when all
    //    items in C are marked (line 12). In such case, a new phase starts
    //    by unmarking all items in C (line 13). Marking and unmarking are
    //    logical operations for partitioning the execution trace of oMCP over
    //    ℓ into phases; they are not part of cache actions in the schedule
    private boolean currentPhaseMark;
    private int markedItemCount;

    // tracking the maximum timestamp that has been seen in the invalidation message
    private long maxInvalidationTimestamp = 0;

    public ConsistentCache(int maxSize) {
        cachedItems = new HashMap<>();
        dummyHead = new ItemNode();
        dummyTail = new ItemNode();
        dummyHead.next = dummyTail;
        dummyTail.prev = dummyHead;

        this.maxSize = maxSize;
        obsoleteItemsPredictor = new SimpleObsoleteItemsPredictor();

        logger.info("ConsistentCache initialized with maxSize={}", maxSize);
    }

    public int getCacheSize() {
        return currentSize;
    }

    public int getCacheSizeLimit() {
        return maxSize;
    }

    public long getItemVersion(String key) {
        if (cachedItems.containsKey(key)) {
            return cachedItems.get(key).version;
        }
        return -1;
    }

    public long getItemValidUntil(String key) {
        if (cachedItems.containsKey(key)) {
            return cachedItems.get(key).validUntil;
        }
        return -1;
    }

    public int getDataSize(List<String> keys) {
        int totalSize = 0;
        for (String key : keys) {
            if (cachedItems.containsKey(key)) {
                totalSize += cachedItems.get(key).dataSize;
            }
        }
        return totalSize;
    }

    public void invalidate(String key, long newVersion) {
        invalidate(key, newVersion, 0);
    }

    public void invalidate(String key, long newVersion, long timeStep) {
        logger.info(
                "ConsistentCache::invalidate - Invalidating item with key={} newVersion={}",
                key, newVersion);

        // update the maximum invalidation timestamp before updating the cache to handle race conditions
        assert newVersion >= maxInvalidationTimestamp;
        maxInvalidationTimestamp = newVersion;

        if (cachedItems.containsKey(key)) {
            ItemNode node = cachedItems.get(key);

            // access write for ML feature
            node.accessTracker.recordWrite(timeStep);

            if (node.validUntil == Long.MAX_VALUE &&
                    node.version < newVersion) {
                node.validUntil = newVersion;
            }
            logger.info(
                    "ConsistentCache::invalidate - Invalidated item with key={}: newVersion={} validUntil={}",
                    key, newVersion, node.validUntil);
        } else {
            logger.info(
                    "ConsistentCache::invalidate - Item with key={} not found in cache",
                    key);
        }

        logger.debug("ConsistentCache::invalidate - Current cache info: {}",
                     this);
    }

    public List<String> getObsoleteItems(Set<String> itemsToKeep,
                                         long timeStep) {
        List<String> obsoleteItems = new ArrayList<>();

        int batchSize = 100;
        List<ItemNode> nodeBatch = new ArrayList<>(batchSize);
        for (Map.Entry<String, ItemNode> entry : cachedItems.entrySet()) {
            String key = entry.getKey();
            ItemNode node = entry.getValue();
            if (!itemsToKeep.contains(key)) {
                node.accessTracker.syncTimeStep(timeStep);

                nodeBatch.add(node);
                if (nodeBatch.size() > batchSize) {
                    List<Boolean> isObsolete =
                            obsoleteItemsPredictor.predictObsoleteItems(
                                    nodeBatch.stream().map(n -> n.accessTracker)
                                            .toList());
                    for (int i = 0; i < nodeBatch.size(); i++) {
                        if (isObsolete.get(i)) {
                            obsoleteItems.add(nodeBatch.get(i).key);
                        }
                    }
                    nodeBatch.clear();
                }
            }
        }

        logger.info("ConsistentCache::getObsoleteItems - Obsolete items: {}",
                    obsoleteItems);
        return obsoleteItems;
    }

    public List<String> getLruItems(Set<String> itemsToKeep, int neededSize) {
        List<String> lruItems = new ArrayList<>();
        ItemNode current = dummyTail.prev;
        int evictedSize = 0;
        while (evictedSize < neededSize && current != dummyHead) {
            if (!itemsToKeep.contains(current.key)) {
                lruItems.add(current.key);
                evictedSize += current.dataSize;
            }
            current = current.prev;
        }
        if (evictedSize < neededSize) {
            logger.warn(
                    "ConsistentCache::getLruItems - Not enough items to evict: neededSize={} evictedSize={}",
                    neededSize, evictedSize);
        }
        logger.info("ConsistentCache::getLruItems - LRU items: {}", lruItems);
        return lruItems;
    }

    public boolean isMCCHit(List<String> keys) {
        long lifeStartMax = Long.MIN_VALUE;
        long lifeEndMin = Long.MAX_VALUE;

        for (String item_key : keys) {
            // not cached
            if (!cachedItems.containsKey(item_key)) {
                return false;
            }

            ItemNode node = cachedItems.get(item_key);

            // invalidation outdated, there can be updates after the invalidation
            if (node.version > maxInvalidationTimestamp) {
                return false;
            }

            // check whether the life cycles of the cached items intersect
            long lifeStart = node.version;
            long lifeEnd = node.validUntil;

            lifeStartMax = max(lifeStart, lifeStartMax);
            lifeEndMin = min(lifeEnd, lifeEndMin);

            if (lifeStartMax >= lifeEndMin) {
                return false;
            }
        }

        return true;
    }

    public MCCHitResult makeMCCHit(List<String> keys) {
        List<String> missingItems = new ArrayList<>();
        List<String> outdatedItems = new ArrayList<>();
        List<String> hitItems = new ArrayList<>();

        for (String key : keys) {
            ItemNode node = cachedItems.getOrDefault(key, null);
            if (node == null) {
                missingItems.add(key);
            } else if (node.version > maxInvalidationTimestamp ||
                    node.validUntil != Long.MAX_VALUE) {
                outdatedItems.add(key);
            } else {
                hitItems.add(key);
            }
        }

        MCCHitResult result = new MCCHitResult();
        result.setMissingItems(missingItems);
        result.setOutdatedItems(outdatedItems);
        result.setHitItems(hitItems);

        logger.info("ConsistentCache::makeMCCHit - MCCHitResult: {}", result);

        return result;
    }

    public void postCacheUpdate(List<String> hitItems,
                                List<String> evictedItems,
                                List<ItemRecord> newItems, long timeStep) {
        for (String key : hitItems) {
            access(key, timeStep);
        }

        for (String key : evictedItems) {
            evict(key);
        }

        for (ItemRecord record : newItems) {
            put(record.getKey(), record.getVersion(), record.getSize(),
                timeStep);
        }

        logger.info(
                "ConsistentCache::postCacheUpdate - hitItems={} evictedItems={} newItems={}",
                hitItems, evictedItems, newItems);
        logger.debug(
                "ConsistentCache::postCacheUpdate - Current cache info: {}",
                this);
    }

    public long getMaxInvalidationTimestamp() {
        return maxInvalidationTimestamp;
    }

    public void markItems(List<String> keys) {
        for (String key : keys) {
            ItemNode node = cachedItems.getOrDefault(key, null);
            if (node != null && !node.isMarked(currentPhaseMark)) {
                node.mark(currentPhaseMark);
                markedItemCount++;
            }
        }
    }

    public boolean isAllMarked() {
        return markedItemCount == itemCount;
    }

    public void startNewPhase() {
        currentPhaseMark = !currentPhaseMark;
        markedItemCount = 0;
    }

    @Override
    public String toString() {
        return "ConsistentCache{currentSize=%d, maxSize=%d, itemCount=%d, maxInvalidationTimestamp=%d, cachedItems=%s}".formatted(
                currentSize, maxSize, itemCount, maxInvalidationTimestamp,
                cachedItems);
    }

    @VisibleForTesting
    void put(String key, long version, int dataSize) {
        put(key, version, dataSize, 0);
    }

    @VisibleForTesting
    void put(String key, long version, int dataSize, long timeStep) {
        if (cachedItems.containsKey(key)) {
            ItemNode node = cachedItems.get(key);
            long oldVersion = node.version;
            long oldValidUntil = node.validUntil;
            long oldDataSize = node.dataSize;

            node.version = version;
            node.validUntil = Long.MAX_VALUE;
            node.accessTracker.recordRead(timeStep);

            currentSize += dataSize - node.dataSize;
            node.dataSize = dataSize;

            removeNode(node);
            addNode(node);

            logger.info(
                    "ConsistentCache::put - Updated item with key={}: oldVersion={} newVersion={} " +
                            "oldValidUntil={} newValidUntil={} oldDataSize={} newDataSize={} ",
                    node.key, oldVersion, node.version, oldValidUntil,
                    node.validUntil, oldDataSize, node.dataSize);
        } else {
            ItemNode node = new ItemNode(key, version, Long.MAX_VALUE, dataSize,
                                         currentPhaseMark, timeStep);
            currentSize += dataSize;
            addNode(node);
            cachedItems.put(key, node);

            node.accessTracker.recordRead(timeStep);

            itemCount++;
            markedItemCount++;

            logger.info(
                    "ConsistentCache::put - Added item with key={}: version={} validUntil={} dataSize={}",
                    node.key, node.version, node.validUntil, node.dataSize);
        }

        logger.debug("ConsistentCache::put - Current cache info: {}", this);
    }

    @VisibleForTesting
    void evict(String key) {
        if (cachedItems.containsKey(key)) {
            ItemNode node = cachedItems.get(key);
            currentSize -= node.dataSize;
            removeNode(node);
            cachedItems.remove(key);
            itemCount--;

            logger.info("ConsistentCache::evict - Evicted item with key={}",
                        key);
        } else {
            logger.info(
                    "ConsistentCache::evict - Item with key={} not found in cache",
                    key);
        }

        logger.debug("ConsistentCache::evict - Current cache info: {}", this);
    }

    @VisibleForTesting
    void access(String key) {
        access(key, 0);
    }

    @VisibleForTesting
    void access(String key, long timeStep) {
        if (cachedItems.containsKey(key)) {
            ItemNode node = cachedItems.get(key);
            removeNode(node);
            addNode(node);
            node.accessTracker.recordRead(timeStep);

            logger.info("ConsistentCache::access - Accessed item with key={}",
                        key);
        } else {
            logger.info(
                    "ConsistentCache::access - Item with key={} not found in cache",
                    key);
        }
        logger.debug("ConsistentCache::access - Current cache info: {}", this);
    }

    @VisibleForTesting
    int getItemCount() {
        return itemCount;
    }

    @VisibleForTesting
    ItemNode getDummyHead() {
        return dummyHead;
    }

    @VisibleForTesting
    ItemNode getDummyTail() {
        return dummyTail;
    }

    private boolean isObsolete(ItemNode node) {
        return node.key.hashCode() % 2 == 0;
    }

    private void removeNode(ItemNode node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void addNode(ItemNode node) {
        node.prev = dummyHead;
        node.next = dummyHead.next;
        dummyHead.next.prev = node;
        dummyHead.next = node;
    }

    @VisibleForTesting
    static class ItemNode {
        @VisibleForTesting
        ItemNode next;
        @VisibleForTesting
        ItemNode prev;
        private long validUntil = Long.MAX_VALUE;
        private String key;
        private long version;
        private int dataSize;
        private boolean mark; // to divide the run into phases

        private AccessTracker accessTracker;

        public ItemNode() {
        }

        public ItemNode(String key, long version, long validUntil, int dataSize,
                        boolean mark, long timeStep) {
            this.key = key;
            this.version = version;
            this.validUntil = validUntil;
            this.dataSize = dataSize;
            this.mark = mark;

            accessTracker = new VariableSizeAccessTracker(256);
            accessTracker.syncTimeStep(timeStep);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof ItemNode other)) {
                return false;
            }
            return key.equals(other.key) && version == other.version &&
                    validUntil == other.validUntil &&
                    dataSize == other.dataSize;
        }

        @Override
        public String toString() {
            return String.format(
                    "ItemNode(key=%s, version=%d, validUntil=%d, dataSize=%d)",
                    key, version, validUntil, dataSize);
        }


        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }

        public long getValidUntil() {
            return validUntil;
        }

        public void setValidUntil(long validUntil) {
            this.validUntil = validUntil;
        }

        public int getDataSize() {
            return dataSize;
        }

        public void setDataSize(int dataSize) {
            this.dataSize = dataSize;
        }

        public void mark(boolean refMark) {
            this.mark = refMark;
        }

        public boolean isMarked(boolean refMark) {
            return this.mark == refMark;
        }
    }

    public static class MCCHitResult {
        private List<String> missingItems;
        private List<String> outdatedItems;
        private List<String> hitItems;

        public List<String> getMissingItems() {
            return missingItems;
        }

        public void setMissingItems(List<String> missingItems) {
            this.missingItems = missingItems;
        }

        public List<String> getOutdatedItems() {
            return outdatedItems;
        }

        public void setOutdatedItems(List<String> outdatedItems) {
            this.outdatedItems = outdatedItems;
        }

        public List<String> getHitItems() {
            return hitItems;
        }

        public void setHitItems(List<String> hitItems) {
            this.hitItems = hitItems;
        }

    }
}
