/*
 * Copyright 2012-2013 Stephane Godbillon (@sgodbillon) and Zenexity
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivemongo.api.indexes

import reactivemongo.bson.{
  BSONBoolean,
  BSONBooleanLike,
  BSONDocument,
  BSONDocumentReader,
  BSONDocumentWriter,
  BSONNumberLike,
  BSONString
}

import reactivemongo.core.protocol.MongoWireVersion

import reactivemongo.api.{
  DB,
  DBMetaCommands,
  BSONSerializationPack,
  Cursor,
  CursorProducer,
  ReadPreference
}
import reactivemongo.api.commands.{ DropIndexes, WriteResult }
import scala.concurrent.{ Future, ExecutionContext }

/** Indexes manager at database level. */
sealed trait IndexesManager {

  /** Gets a future list of all the index on this database. */
  def list(): Future[List[NSIndex]]

  /**
   * Creates the given index only if it does not exist on this database.
   *
   * The following rules are used to check the matching index:
   * - if `nsIndex.isDefined`, it checks using the index name,
   * - otherwise it checks using the key.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param nsIndex the index to create
   *
   * @return a future containing true if the index was created, false if it already exists.
   */
  def ensure(nsIndex: NSIndex): Future[Boolean]

  /**
   * Creates the given index.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param nsIndex The index to create.
   */
  def create(nsIndex: NSIndex): Future[WriteResult]

  /**
   * Drops the given index on the given collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(nsIndex: NSIndex): Future[Int] =
    drop(nsIndex.collectionName, nsIndex.index.eventualName)

  /**
   * Drops the given index on the given collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(collectionName: String, indexName: String): Future[Int]

  /**
   * Drops all the indexes on the given collection.
   */
  def dropAll(collectionName: String): Future[Int]

  /** Gets a manager for the given collection. */
  def onCollection(name: String): CollectionIndexesManager
}

/**
 * A helper class to manage the indexes on a Mongo 2.x database.
 *
 * @param db The subject database.
 */
final class LegacyIndexesManager(db: DB)(
  implicit
  context: ExecutionContext) extends IndexesManager {

  val collection = db("system.indexes")

  def list(): Future[List[NSIndex]] = collection.find(BSONDocument()).cursor(db.connection.options.readPreference)(IndexesManager.NSIndexReader, CursorProducer.defaultCursorProducer).collect[List](-1, Cursor.FailOnError[List[NSIndex]]())

  def ensure(nsIndex: NSIndex): Future[Boolean] = {
    val query = BSONDocument(
      "ns" -> nsIndex.namespace,
      "name" -> nsIndex.index.eventualName)

    collection.find(query).one.flatMap { idx =>
      if (!idx.isDefined) {
        create(nsIndex).map(_ => true)
      } else {
        Future.successful(false)
      }
    }
  }

  def create(nsIndex: NSIndex): Future[WriteResult] = {
    implicit val writer = IndexesManager.NSIndexWriter
    collection.insert(nsIndex)
  }

  def drop(collectionName: String, indexName: String): Future[Int] = {
    import reactivemongo.api.commands.bson.BSONDropIndexesImplicits._
    db.collection(collectionName).
      runValueCommand(DropIndexes(indexName), ReadPreference.primary)
  }

  def dropAll(collectionName: String): Future[Int] = drop(collectionName, "*")

  def onCollection(name: String): CollectionIndexesManager =
    new LegacyCollectionIndexesManager(db.name, name, this)
}

/**
 * A helper class to manage the indexes on a Mongo 3.x database.
 *
 * @param db The subject database.
 */
final class DefaultIndexesManager(db: DB with DBMetaCommands)(
  implicit
  context: ExecutionContext) extends IndexesManager {

  private def listIndexes(collections: List[String], indexes: List[NSIndex]): Future[List[NSIndex]] = collections match {
    case c :: cs => onCollection(c).list().flatMap(ix =>
      listIndexes(cs, indexes ++ ix.map(NSIndex(s"${db.name}.$c", _))))

    case _ => Future.successful(indexes)
  }

  def list(): Future[List[NSIndex]] =
    db.collectionNames.flatMap(listIndexes(_, Nil))

  def ensure(nsIndex: NSIndex): Future[Boolean] =
    onCollection(nsIndex.collectionName).ensure(nsIndex.index)

  def create(nsIndex: NSIndex): Future[WriteResult] =
    onCollection(nsIndex.collectionName).create(nsIndex.index)

  def drop(collectionName: String, indexName: String): Future[Int] =
    onCollection(collectionName).drop(indexName)

  def dropAll(collectionName: String): Future[Int] =
    onCollection(collectionName).dropAll()

  def onCollection(name: String): CollectionIndexesManager =
    new DefaultCollectionIndexesManager(db, name)
}

sealed trait CollectionIndexesManager {
  /** Returns the list of indexes for the current collection. */
  def list(): Future[List[Index]]

  /**
   * Creates the given index only if it does not exist on this collection.
   *
   * The following rules are used to check the matching index:
   * - if `nsIndex.isDefined`, it checks using the index name,
   * - otherwise it checks using the key.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param index The index to create.
   *
   * @return a future containing true if the index was created, false if it already exists.
   */
  def ensure(index: Index): Future[Boolean]

  /**
   * Creates the given index.
   *
   * Warning: given the options you choose, and the data to index, it can be a long and blocking operation on the database.
   * You should really consider reading [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this, especially in production.
   *
   * @param index The index to create.
   */
  def create(index: Index): Future[WriteResult]

  /**
   * Drops the given index on that collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(nsIndex: NSIndex): Future[Int]

  /**
   * Drops the given index on that collection.
   *
   * @return The number of indexes that were dropped.
   */
  def drop(indexName: String): Future[Int]

  /**
   * Drops all the indexes on that collection.
   */
  def dropAll(): Future[Int]
}

private class LegacyCollectionIndexesManager(
  db: String, collectionName: String, legacy: LegacyIndexesManager)(
  implicit
  context: ExecutionContext) extends CollectionIndexesManager {

  val fqName = db + "." + collectionName

  def list(): Future[List[Index]] =
    legacy.list.map(_.filter(_.namespace == fqName).map(_.index))

  def ensure(index: Index): Future[Boolean] =
    legacy.ensure(NSIndex(fqName, index))

  def create(index: Index): Future[WriteResult] =
    legacy.create(NSIndex(fqName, index))

  @deprecated("Use [[IndexesManager.drop]]", "0.11.0")
  def drop(nsIndex: NSIndex): Future[Int] = legacy.drop(nsIndex)

  def drop(indexName: String): Future[Int] =
    legacy.drop(collectionName, indexName)

  def dropAll(): Future[Int] = legacy.dropAll(collectionName)
}

private class DefaultCollectionIndexesManager(db: DB, collectionName: String)(
  implicit
  context: ExecutionContext) extends CollectionIndexesManager {

  import reactivemongo.api.commands.{
    CreateIndexes,
    Command,
    ListIndexes
  }
  import reactivemongo.api.commands.bson.BSONListIndexesImplicits._
  import reactivemongo.api.commands.bson.BSONCreateIndexesImplicits._
  import reactivemongo.api.commands.bson.
    BSONCommonWriteCommandsImplicits.DefaultWriteResultReader

  private lazy val collection = db(collectionName)
  private lazy val listCommand = ListIndexes(db.name)

  private lazy val runner =
    Command.run(BSONSerializationPack, db.failoverStrategy)

  def list(): Future[List[Index]] =
    runner(
      collection, listCommand, ReadPreference.primary).recoverWith {
      case err: WriteResult if err.code.exists(_ == 26 /* no database */ ) =>
        Future.successful(List.empty[Index])

      case err => Future.failed(err)
    }

  def ensure(index: Index): Future[Boolean] = list().flatMap { indexes =>
    val idx = index.name match {
      case Some(n) => indexes.find(_.name.exists(_ == n))
      case _       => indexes.find(_.key == index.key)
    }

    if (!idx.isDefined) {
      create(index).map(_ => true)
    } else {
      Future.successful(false)
    }
  }

  implicit private val writeResultReader =
    BSONDocumentReader[WriteResult] { DefaultWriteResultReader.read(_) }

  def create(index: Index): Future[WriteResult] =
    runner(
      collection,
      CreateIndexes(db.name, List(index)),
      ReadPreference.primary)

  @deprecated("Use [[IndexesManager.drop]]", "0.11.0")
  def drop(nsIndex: NSIndex): Future[Int] = {
    import reactivemongo.api.commands.bson.BSONDropIndexesImplicits._
    runner(
      db(nsIndex.collectionName),
      DropIndexes(nsIndex.index.eventualName),
      ReadPreference.primary).map(_.value)
  }

  def drop(indexName: String): Future[Int] = {
    import reactivemongo.api.commands.bson.BSONDropIndexesImplicits._
    runner(
      collection, DropIndexes(indexName), ReadPreference.primary).map(_.value)
  }

  @inline def dropAll(): Future[Int] = drop("*")
}

/** Factory for indexes manager scoped with a specified collection. */
object CollectionIndexesManager {
  /**
   * Returns an indexes manager for specified collection.
   *
   * @param db the database
   * @param collectionName the collection name
   */
  def apply(db: DB, collectionName: String)(implicit context: ExecutionContext): CollectionIndexesManager = {
    val wireVer = db.connection.metadata.map(_.maxWireVersion)

    if (wireVer.exists(_ >= MongoWireVersion.V30)) {
      new DefaultCollectionIndexesManager(db, collectionName)
    } else new LegacyCollectionIndexesManager(db.name, collectionName,
      new LegacyIndexesManager(db))
  }
}

object IndexesManager {
  import reactivemongo.util.option

  /**
   * Returns an indexes manager for specified database.
   *
   * @param db the database
   */
  def apply(db: DB with DBMetaCommands)(implicit context: ExecutionContext): IndexesManager = {
    val wireVer = db.connection.metadata.map(_.maxWireVersion)

    if (wireVer.exists(_ >= MongoWireVersion.V30)) new DefaultIndexesManager(db)
    else new LegacyIndexesManager(db)
  }

  protected def toBSONDocument(nsIndex: NSIndex) = {
    BSONDocument(
      "ns" -> BSONString(nsIndex.namespace),
      "name" -> BSONString(nsIndex.index.eventualName),
      "key" -> BSONDocument(
        (for (kv <- nsIndex.index.key)
          yield kv._1 -> kv._2.value).toStream),
      "background" -> option(nsIndex.index.background, BSONBoolean(true)),
      "dropDups" -> option(nsIndex.index.dropDups, BSONBoolean(true)),
      "sparse" -> option(nsIndex.index.sparse, BSONBoolean(true)),
      "unique" -> option(nsIndex.index.unique, BSONBoolean(true)),
      "partialFilterExpression" -> nsIndex.index.partialFilter) ++ nsIndex.index.options
  }

  implicit object NSIndexWriter extends BSONDocumentWriter[NSIndex] {
    def write(nsIndex: NSIndex): BSONDocument = {
      if (nsIndex.index.key.isEmpty) {
        throw new RuntimeException("the key should not be empty!")
      }

      toBSONDocument(nsIndex)
    }
  }

  implicit object IndexReader extends BSONDocumentReader[Index] {
    def read(doc: BSONDocument): Index = doc.getAs[BSONDocument]("key").map(
      _.elements.map { elem => elem.name -> IndexType(elem.value) }.toList).
      fold[Index](throw new Exception("the key must be defined")) { k =>
        val key = doc.getAs[BSONDocument]("weights").fold(k) { w =>
          val fields = w.elements.map(_.name)

          (k, fields).zipped.map {
            case ((_, tpe), name) => name -> tpe
          }.toList
        }

        val options = doc.elements.filterNot { element =>
          element.name == "ns" || element.name == "key" ||
            element.name == "name" || element.name == "unique" ||
            element.name == "background" || element.name == "dropDups" ||
            element.name == "sparse" || element.name == "v" ||
            element.name == "partialFilterExpression"
        }.toSeq

        (for {
          name <- doc.getAsUnflattenedTry[String]("name")
          unique <- doc.getAsUnflattenedTry[BSONBooleanLike]("unique").
            map(_.fold(false)(_.toBoolean))

          background <- doc.getAsUnflattenedTry[BSONBooleanLike]("background").
            map(_.fold(false)(_.toBoolean))

          dropDups <- doc.getAsUnflattenedTry[BSONBooleanLike]("dropDups").
            map(_.fold(false)(_.toBoolean))

          sparse <- doc.getAsUnflattenedTry[BSONBooleanLike]("sparse").
            map(_.fold(false)(_.toBoolean))

          version <- doc.getAsUnflattenedTry[BSONNumberLike]("v").
            map(_.map(_.toInt))

          partialFilter <- doc.getAsUnflattenedTry[BSONDocument](
            "partialFilterExpression")
        } yield Index(key, name, unique, background, dropDups,
          sparse, version, partialFilter, BSONDocument(options))).get
      }
  }

  implicit object NSIndexReader extends BSONDocumentReader[NSIndex] {
    def read(doc: BSONDocument): NSIndex =
      doc.getAs[BSONString]("ns").map(_.value).fold[NSIndex](
        throw new Exception("the namespace ns must be defined"))(
          NSIndex(_, doc.as[Index]))
  }
}
