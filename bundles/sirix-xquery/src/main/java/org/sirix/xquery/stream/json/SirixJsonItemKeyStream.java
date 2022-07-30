package org.sirix.xquery.stream.json;

import org.brackit.xquery.xdm.Item;
import org.brackit.xquery.xdm.Stream;
import org.jetbrains.annotations.Nullable;
import org.sirix.api.json.JsonNodeReadOnlyTrx;
import org.sirix.index.redblacktree.keyvalue.NodeReferences;
import org.sirix.xquery.json.JsonDBCollection;
import org.sirix.xquery.json.JsonItemFactory;

import java.util.Iterator;

import static com.google.common.base.Preconditions.checkNotNull;

public final class SirixJsonItemKeyStream implements Stream<Item> {

  private static final JsonItemFactory itemFactory = new JsonItemFactory();

  private final Iterator<NodeReferences> iter;

  private final JsonDBCollection collection;

  private final JsonNodeReadOnlyTrx rtx;

  private Iterator<Long> nodeKeys;

  public SirixJsonItemKeyStream(final Iterator<NodeReferences> iter, final JsonDBCollection collection,
      final JsonNodeReadOnlyTrx rtx) {
    this.iter = checkNotNull(iter);
    this.collection = checkNotNull(collection);
    this.rtx = checkNotNull(rtx);
  }

  @Override
  public Item next() {
    if (nodeKeys == null || !nodeKeys.hasNext()) {
      if (iter.hasNext()) {
        final NodeReferences nodeReferences = iter.next();
        nodeKeys = nodeReferences.getNodeKeys().iterator();
        return getItem();
      }
    } else {
      return getItem();
    }
    return null;
  }

  @Nullable
  private Item getItem() {
    if (nodeKeys.hasNext()) {
      final long nodeKey = nodeKeys.next();
      rtx.moveTo(nodeKey);
      return itemFactory.getSequence(rtx, collection);
    }
    return null;
  }

  @Override
  public void close() {}

}
