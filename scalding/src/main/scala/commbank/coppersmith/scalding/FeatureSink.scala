//
// Copyright 2016 Commonwealth Bank of Australia
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//        http://www.apache.org/licenses/LICENSE-2.0
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//

package commbank.coppersmith.scalding

import com.twitter.scalding.typed.TypedPipe
import com.twitter.scalding.{Execution, TupleSetter}

import org.apache.hadoop.fs.Path

import scalaz.NonEmptyList
import scalaz.std.list.listInstance
import scalaz.syntax.std.list.ToListOpsFromList
import scalaz.syntax.traverse.ToTraverseOps

import au.com.cba.omnia.maestro.api._, Maestro._

import commbank.coppersmith.Feature._
import commbank.coppersmith.FeatureValue


import Partitions.PathComponents
import HiveSupport.{DelimiterConflictStrategy, FailJob}

import FeatureSink.WriteResult

trait FeatureSink {
  /**
    * Persist feature values, returning the list of paths written (for committing at the
    * end of the job) or an error if trying to write to a path that is already committed
    */
  def write(features: TypedPipe[(FeatureValue[_], Time)]): WriteResult
}

object FeatureSink {
  sealed trait WriteError
  case class AlreadyCommitted(paths: NonEmptyList[Path]) extends WriteError
  case class AttemptedWriteToCommitted(path: Path) extends WriteError

  type WriteResult = Execution[Either[WriteError, Set[Path]]]

  def commitFlag(path: Path) = new Path(path, "_SUCCESS")
  def isCommitted(path: Path): Execution[Boolean] = Execution.fromHdfs(Hdfs.exists(commitFlag(path)))

  type CommitResult = Execution[Either[WriteError, Unit]]
  // Note: Check for committed flags and subsequent writing thereof is not atomic
  def commit(paths: Set[Path]): CommitResult = {

    // Check all paths for committed state first. Avoids committing earlier paths
    // if a latter path is already committed and would fail the job overall.
    val pathCommits: Execution[List[(Path, Boolean)]] =
      paths.toList.map(p => isCommitted(p).map((p, _))).sequence

    pathCommits.flatMap(pathCommitStates => {
      val committedPaths = pathCommitStates.collect { case (path, true) => path }
      committedPaths.toNel.map(committed =>
        Execution.from(Left(AlreadyCommitted(committed)))
      ).getOrElse(
        paths.toList.map(path =>
          Execution.fromHdfs(Hdfs.create(commitFlag(path)))
        ).sequence.unit.map(Right(_))
      )
    })
  }
}

sealed trait SinkPartition[T] {
  type P
  def pathComponents: PathComponents[P]
  def tupleSetter: TupleSetter[P]
  def underlying: Partition[T, P]
}

final case class FixedSinkPartition[T, PP : PathComponents : TupleSetter](
  fieldNames: List[String],
  pathPattern: String,
  partitionValue: PP
) extends SinkPartition[T] {
  type P = PP
  def pathComponents = implicitly
  def tupleSetter = implicitly
  def underlying = Partition(fieldNames, _ => partitionValue, pathPattern)
}

final case class DerivedSinkPartition[T, PP : PathComponents : TupleSetter](
  underlying: Partition[T, PP]
) extends SinkPartition[T] {
  type P = PP
  def pathComponents = implicitly
  def tupleSetter = implicitly
}

trait Encode[D, E] {
  def encode(d: D): E
}
trait FeatureValueEnc[T] extends Encode[(FeatureValue[_], Time), T] {
  def encode(fvt: (FeatureValue[_], Time)): T
}

object TextSink {
  type DatabaseName = String
  type TableName    = String

  val NullValue = "\\N"
  val Delimiter = "|"

  def configure[T <: ThriftStruct with Product : FeatureValueEnc : Manifest](
    dbPrefix: String,
    dbRoot: Path,
    tableName: TableName,
    partition: SinkPartition[T],
    group: Option[String] = None,
    dcs: DelimiterConflictStrategy[T] = FailJob[T]()
  ): TextSink[T] =
    TextSink(
      Config(
        s"${dbPrefix}_features",
        new Path(dbRoot, s"view/warehouse/features/${group.map(_ + "/").getOrElse("")}$tableName"),
        tableName,
        partition,
        dcs
      )
    )

  case class Config[T <: ThriftStruct with Product : FeatureValueEnc : Manifest](
    dbName:    DatabaseName,
    tablePath: Path,
    tableName: TableName,
    partition: SinkPartition[T],
    dcs:       DelimiterConflictStrategy[T] = FailJob[T]()
  ) {
    def hiveConfig =
      HiveSupport.HiveConfig[T, partition.P](
        partition.underlying,
        dbName,
        tablePath,
        tableName,
        TextSink.Delimiter,
        dcs
      )
  }

}

case class TextSink[
  T <: ThriftStruct with Product : FeatureValueEnc : Manifest
](conf: TextSink.Config[T]) extends FeatureSink {
  def write(features: TypedPipe[(FeatureValue[_], Time)]) = {
    val textPipe = features.map(implicitly[FeatureValueEnc[T]].encode)

    implicit val pathComponents: PathComponents[conf.partition.P] = conf.partition.pathComponents
    // Note: This needs to be explicitly specified so that the TupleSetter.singleSetter
    // instance isn't used (causing a failure at runtime).
    implicit val tupleSetter: TupleSetter[conf.partition.P] = conf.partition.tupleSetter
    HiveSupport.writeTextTable(conf.hiveConfig, textPipe)
  }
}
