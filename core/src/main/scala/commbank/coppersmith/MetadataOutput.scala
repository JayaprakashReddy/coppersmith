package commbank.coppersmith

import commbank.coppersmith.Feature.Value
import Feature._
import Metadata._

object MetadataOutput {
  case class MetadataPrinter (
    fn: (Metadata[_, Feature.Value], Option[Conforms[_, _]]) => String,
    combiner: List[String] => String = defaultCombiner)

  val defaultCombiner = (list: List[String]) => list.mkString("\n")

  private def hydroValueTypeToString(v: ValueType) = v match {
    case ValueType.IntegralType => "int"
    case ValueType.DecimalType  => "double"
    case ValueType.StringType   => "string"
  }

  private def hydroFeatureTypeToString(f: Feature.Type) = f match {
    case t : Type.Categorical => "categorical"
    case t : Type.Numeric     => "continuous"
  }

  private def genericFeatureTypeToString(f: Feature.Type) = f.toString.toLowerCase

  private def genericValueTypeToString(v: ValueType) = v.toString.replace("Type", "").toLowerCase

  val HydroPsv: MetadataPrinter = MetadataPrinter((m, _) => {
      val valueType = hydroValueTypeToString(m.valueType)
      val featureType = hydroFeatureTypeToString(m.featureType)
      List(m.namespace + "." + m.name, valueType, featureType).map(_.toLowerCase).mkString("|")
  })


  val JsonObject: MetadataPrinter = MetadataPrinter((md, oConforms) => {

    import argonaut._, Argonaut._

    Json(
      "name" -> jString(md.name),
      "namespace" -> jString(md.namespace),
      "description" -> jString(md.description),
      "source" -> jString(md.sourceType.toString),
      "featureType" -> jString(genericFeatureTypeToString(md.featureType)),
      "valueType" -> jString(genericValueTypeToString(md.valueType)),
      "typesConform" -> jBool(oConforms.isDefined)
    ).nospaces
  }, lst => s"[${lst.mkString(",")}}]")


  trait HasMetadata[S] {
    def metadata: Iterable[Metadata[S, Value]]
  }

  implicit def fromFeature[S, V <: Value](f:Feature[S, V]): HasMetadata[S] = new HasMetadata[S] {
    def metadata: Iterable[Metadata[S, V]] = Seq(f.metadata)
  }

  implicit def fromMetadataSet[S](mds: MetadataSet[S]) = new HasMetadata[S] {
    def metadata = mds.metadata
  }

  def metadataString[S](
    metadata: List[(Metadata[S, Feature.Value], Option[Conforms[_, _]])],
    printer: MetadataPrinter
  ): String = {
    printer.combiner(metadata.map(printer.fn.tupled))
  }
}
