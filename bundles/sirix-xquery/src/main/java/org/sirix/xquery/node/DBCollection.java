package org.sirix.xquery.node;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnegative;
import javax.annotation.Nullable;
import javax.xml.stream.XMLEventReader;
import org.brackit.xquery.node.AbstractCollection;
import org.brackit.xquery.node.parser.CollectionParser;
import org.brackit.xquery.node.parser.SubtreeHandler;
import org.brackit.xquery.node.parser.SubtreeParser;
import org.brackit.xquery.node.stream.ArrayStream;
import org.brackit.xquery.xdm.AbstractTemporalNode;
import org.brackit.xquery.xdm.DocumentException;
import org.brackit.xquery.xdm.OperationNotSupportedException;
import org.brackit.xquery.xdm.Stream;
import org.brackit.xquery.xdm.TemporalCollection;
import org.sirix.access.Databases;
import org.sirix.access.ResourceConfiguration;
import org.sirix.api.Database;
import org.sirix.api.Transaction;
import org.sirix.api.xml.XmlResourceManager;
import org.sirix.api.xml.XmlNodeReadOnlyTrx;
import org.sirix.api.xml.XmlNodeTrx;
import org.sirix.exception.SirixException;
import org.sirix.exception.SirixIOException;
import org.sirix.service.xml.shredder.InsertPosition;
import org.sirix.utils.LogWrapper;
import org.slf4j.LoggerFactory;
import com.google.common.base.Preconditions;

/**
 * Database collection.
 *
 * @author Johannes Lichtenberger
 *
 */
public final class DBCollection extends AbstractCollection<AbstractTemporalNode<DBNode>>
    implements TemporalCollection<AbstractTemporalNode<DBNode>>, AutoCloseable {

  /** Logger. */
  private static final LogWrapper LOGGER = new LogWrapper(LoggerFactory.getLogger(DBCollection.class));

  /** ID sequence. */
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  /** {@link Sirix} database. */
  private final Database<XmlResourceManager> mDatabase;

  /** Unique ID. */
  private final int mID;

  /**
   * Constructor.
   *
   * @param name collection name
   * @param database Sirix {@link Database} reference
   */
  public DBCollection(final String name, final Database<XmlResourceManager> database) {
    super(Preconditions.checkNotNull(name));
    mDatabase = Preconditions.checkNotNull(database);
    mID = ID_SEQUENCE.incrementAndGet();
  }

  public Transaction beginTransaction() {
    return mDatabase.beginTransaction();
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other)
      return true;

    if (!(other instanceof DBCollection))
      return false;

    final DBCollection coll = (DBCollection) other;
    return mDatabase.equals(coll.mDatabase);
  }

  @Override
  public int hashCode() {
    return mDatabase.hashCode();
  }

  /**
   * Get the unique ID.
   *
   * @return unique ID
   */
  public int getID() {
    return mID;
  }

  /**
   * Get the underlying Sirix {@link Database}.
   *
   * @return Sirix {@link Database}
   */
  public Database<XmlResourceManager> getDatabase() {
    return mDatabase;
  }

  @Override
  public DBNode getDocument(Instant pointInTime) {
    return getDocument(pointInTime, name, false);
  }

  @Override
  public DBNode getDocument(Instant pointInTime, boolean updatable) {
    return getDocument(pointInTime, name, updatable);
  }

  @Override
  public DBNode getDocument(Instant pointInTime, String name) {
    return getDocument(pointInTime, name, false);
  }

  @Override
  public DBNode getDocument(Instant pointInTime, String name, boolean updatable) {
    try {
      return getDocumentInternal(name, pointInTime, updatable);
    } catch (final SirixException e) {
      throw new DocumentException(e.getCause());
    }
  }

  private DBNode getDocumentInternal(final String resName, final Instant pointInTime, final boolean updatable) {
    final XmlResourceManager resource = mDatabase.openResourceManager(resName);

    XmlNodeReadOnlyTrx trx;

    if (updatable) {
      if (resource.hasRunningNodeWriteTrx()) {
        final Optional<XmlNodeTrx> optionalWriteTrx = resource.getNodeWriteTrx();

        if (optionalWriteTrx.isPresent()) {
          trx = optionalWriteTrx.get();
        } else {
          trx = resource.beginNodeTrx();
        }
      } else {
        trx = resource.beginNodeTrx();
      }

      final int revision = resource.getRevisionNumber(pointInTime);

      if (revision < resource.getMostRecentRevisionNumber()) {
        final XmlNodeTrx wtx = (XmlNodeTrx) trx;
        if (wtx.revertTo(revision).getRevisionTimestamp().isAfter(pointInTime)) {
          if (revision - 1 >= 1) {
            wtx.revertTo(revision - 1);
          } else {
            wtx.close();

            return null;
          }
        }
      }
    } else {
      trx = resource.beginNodeReadOnlyTrx(pointInTime);

      if (trx.getRevisionTimestamp().isAfter(pointInTime)) {
        final int revision = trx.getRevisionNumber();

        if (revision > 1) {
          trx.close();

          trx = resource.beginNodeReadOnlyTrx(revision - 1);
        } else {
          trx.close();

          return null;
        }
      }
    }

    return new DBNode(trx, this);
  }

  @Override
  public void delete() {
    try {
      Databases.removeDatabase(mDatabase.getDatabaseConfig().getFile());
    } catch (final SirixIOException e) {
      throw new DocumentException(e.getCause());
    }
  }

  @Override
  public void remove(final long documentID) {
    if (documentID >= 0) {
      final String resource = mDatabase.getResourceName((int) documentID);
      if (resource != null) {
        mDatabase.removeResource(resource);
      }
    }
  }

  @Override
  public DBNode getDocument(final @Nonnegative int revision) {
    final List<Path> resources = mDatabase.listResources();
    if (resources.size() > 1) {
      throw new DocumentException("More than one document stored in database/collection!");
    }
    try {
      final XmlResourceManager manager = mDatabase.openResourceManager(resources.get(0).getFileName().toString());
      final int version = revision == -1
          ? manager.getMostRecentRevisionNumber()
          : revision;
      final XmlNodeReadOnlyTrx rtx = manager.beginNodeReadOnlyTrx(version);
      return new DBNode(rtx, this);
    } catch (final SirixException e) {
      throw new DocumentException(e.getCause());
    }
  }

  public DBNode add(final String resName, SubtreeParser parser)
      throws OperationNotSupportedException, DocumentException {
    try {
      final String resource =
          new StringBuilder(2).append("resource").append(mDatabase.listResources().size() + 1).toString();
      mDatabase.createResource(ResourceConfiguration.newBuilder(resource)
                                                    .useDeweyIDs(true)
                                                    .useTextCompression(true)
                                                    .buildPathSummary(true)
                                                    .build());
      final XmlResourceManager manager = mDatabase.openResourceManager(resource);
      final XmlNodeTrx wtx = manager.beginNodeTrx();
      final SubtreeHandler handler =
          new SubtreeBuilder(this, wtx, InsertPosition.AS_FIRST_CHILD, Collections.emptyList());

      // Make sure the CollectionParser is used.
      if (!(parser instanceof CollectionParser)) {
        parser = new CollectionParser(parser);
      }

      parser.parse(handler);
      return new DBNode(wtx, this);
    } catch (final SirixException e) {
      LOGGER.error(e.getMessage(), e);
      return null;
    }
  }

  @Override
  public DBNode add(SubtreeParser parser) throws OperationNotSupportedException, DocumentException {
    try {
      final String resourceName =
          new StringBuilder(2).append("resource").append(mDatabase.listResources().size() + 1).toString();
      mDatabase.createResource(ResourceConfiguration.newBuilder(resourceName)
                                                    .useDeweyIDs(true)
                                                    .useTextCompression(true)
                                                    .buildPathSummary(true)
                                                    .build());
      final XmlResourceManager resource = mDatabase.openResourceManager(resourceName);
      final XmlNodeTrx wtx = resource.beginNodeTrx();

      final SubtreeHandler handler =
          new SubtreeBuilder(this, wtx, InsertPosition.AS_FIRST_CHILD, Collections.emptyList());

      // Make sure the CollectionParser is used.
      if (!(parser instanceof CollectionParser)) {
        parser = new CollectionParser(parser);
      }

      parser.parse(handler);
      return new DBNode(wtx, this);
    } catch (final SirixException e) {
      LOGGER.error(e.getMessage(), e);
      return null;
    }
  }

  public DBNode add(final String resourceName, final XMLEventReader reader)
      throws OperationNotSupportedException, DocumentException {
    try {
      mDatabase.createResource(ResourceConfiguration.newBuilder(resourceName).useDeweyIDs(true).build());
      final XmlResourceManager resource = mDatabase.openResourceManager(resourceName);
      final XmlNodeTrx wtx = resource.beginNodeTrx();
      wtx.insertSubtreeAsFirstChild(reader);
      wtx.moveToDocumentRoot();
      return new DBNode(wtx, this);
    } catch (final SirixException e) {
      LOGGER.error(e.getMessage(), e);
      return null;
    }
  }

  @Override
  public void close() throws SirixException {
    mDatabase.close();
  }

  @Override
  public long getDocumentCount() {
    return mDatabase.listResources().size();
  }

  @Override
  public DBNode getDocument() {
    return getDocument(-1);
  }

  @Override
  public Stream<DBNode> getDocuments() {
    return getDocuments(false);
  }

  @Override
  public DBNode getDocument(final int revision, final String name) {
    return getDocument(revision, name, false);
  }

  @Override
  public DBNode getDocument(final String name) {
    return getDocument(-1, name, false);
  }

  @Override
  public DBNode getDocument(final int revision, final String name, final boolean updatable) {
    try {
      return getDocumentInternal(name, revision, updatable);
    } catch (final SirixException e) {
      throw new DocumentException(e.getCause());
    }
  }

  private DBNode getDocumentInternal(final String resName, final int revision, final boolean updatable) {
    final XmlResourceManager resource = mDatabase.openResourceManager(resName);
    final int version = revision == -1
        ? resource.getMostRecentRevisionNumber()
        : revision;

    final XmlNodeReadOnlyTrx trx;
    if (updatable) {
      if (resource.hasRunningNodeWriteTrx()) {
        final Optional<XmlNodeTrx> optionalWriteTrx = resource.getNodeWriteTrx();

        if (optionalWriteTrx.isPresent()) {
          trx = optionalWriteTrx.get();
        } else {
          trx = resource.beginNodeTrx();
        }
      } else {
        trx = resource.beginNodeTrx();
      }

      if (version < resource.getMostRecentRevisionNumber())
        ((XmlNodeTrx) trx).revertTo(version);
    } else {
      trx = resource.beginNodeReadOnlyTrx(version);
    }

    return new DBNode(trx, this);
  }

  @Override
  public DBNode getDocument(final int revision, final boolean updatable) {
    final List<Path> resources = mDatabase.listResources();
    if (resources.size() > 1) {
      throw new DocumentException("More than one document stored in database/collection!");
    }
    try {
      return getDocumentInternal(resources.get(0).getFileName().toString(), revision, updatable);
    } catch (final SirixException e) {
      throw new DocumentException(e.getCause());
    }
  }

  @Override
  public Stream<DBNode> getDocuments(final boolean updatable) {
    final List<Path> resources = mDatabase.listResources();
    final List<DBNode> documents = new ArrayList<>(resources.size());

    resources.forEach(resourcePath -> {
      try {
        final String resourceName = resourcePath.getFileName().toString();
        final XmlResourceManager resource = mDatabase.openResourceManager(resourceName);
        final XmlNodeReadOnlyTrx trx = updatable
            ? resource.beginNodeTrx()
            : resource.beginNodeReadOnlyTrx();
        documents.add(new DBNode(trx, this));
      } catch (final SirixException e) {
        throw new DocumentException(e.getCause());
      }
    });

    return new ArrayStream<>(documents.toArray(new DBNode[documents.size()]));
  }

  @Override
  public DBNode getDocument(boolean updatabale) {
    return getDocument(-1, updatabale);
  }
}
