package scala.meta.internal.metals.config

object DoctorFormat {
  sealed trait DoctorFormat
  case object Html extends DoctorFormat
  case object Json extends DoctorFormat

  def fromString(value: String): Option[DoctorFormat] =
    value match {
      case "html" => Some(Html)
      case "json" => Some(Json)
      case _ => None
    }

}
