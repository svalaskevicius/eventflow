package lib

import scala.collection.immutable.Seq

object Hack {
  import scala.meta._

  @scala.annotation.compileTimeOnly("scalaplayground.macros not expanded")
  class Mainp extends scala.annotation.StaticAnnotation {
    inline def apply(defn: Any): Any = meta {
      println(defn)
      defn
    }
  }
  @scala.annotation.compileTimeOnly("scalaplayground.macros not expanded")
  class state extends scala.annotation.StaticAnnotation {
    inline def apply(defn: Any): Any = meta {
      defn match {
        case q"..$mods def $name ( ..$args ): $typ = { ..$lines }" => transform(defn, mods, name, args, typ, lines)
        case q"..$mods def $name: $typ = { ..$lines }" => transform(defn, mods, name, Seq.empty, typ, lines)
        case q"..$mods def $name ( ..$args ): $typ = $line" => transform(defn, mods, name, args, typ, Seq(line))
        case q"..$mods def $name: $typ = $line" => transform(defn, mods, name, Seq.empty, typ, Seq(line))
        case _ =>
          println(defn)
          abort("wrong state syntax")
      }
    }
  }
  private def transform(defn: Any, mods: Seq[Mod], name: Term.Name, args: Seq[Term.Param], typ: Option[Type], lines: Seq[Stat]) = {
//    import scala.reflect.ClassTag
//    def tnf[T](v: T)(implicit ev: ClassTag[T]) = ev.toString
//    def pfn[T](x: T, n: Int = 0)(implicit ev: ClassTag[T])  = println((" " * n) + s"=======: ${ev.runtimeClass.getCanonicalName} :========\n${" " * n}$x\n")
//    pfn(name)
//    pfn(args)
//    args.foreach{ xx =>
//      pfn(xx, 4)
//      xx.decltpe.foreach(xxx=> pfn(xxx, 8))
//    }
//    pfn(typ)
//    pfn(lines)
//    pfn(defn)

    lazy val argTypes = args.map(_.decltpe).map {
      case Some(targ"${tpe: Type}") => tpe
      case _ => abort("argument type is unknown")
    }
    val llines = lines.map {
      case q"${ll: Term}" => ll
      case _ => abort("bobobo")
    }

    val ret = args.length match {
      case 0 => q"()"
        q"..$mods val ${Pat.Var.Term(name)} : RegisteredFlowStateAux[Unit] = ref(scala.Symbol(${name.value}), handler(..$llines))"
      case 1 =>
        q"..$mods val ${Pat.Var.Term(name)} : RegisteredFlowStateAux[..$argTypes] = ref(scala.Symbol(${name.value}), (${args.head}) => handler(..$llines))"
      case _ =>
        val varPattern = Pat.Tuple(args.map((x: Term.Param) =>
          Pat.Var.Term(Term.Name(x.name.value))
        ))
        q"..$mods val ${scala.meta.Pat.Var.Term(name)} : RegisteredFlowStateAux[(..$argTypes)] = ref(scala.Symbol(${name.value}), {case ($varPattern) => handler(..$llines) })"
    }
//    println(s"\n\nbefore:\n$defn\nafter:\n$ret")
    ret
  }
}
