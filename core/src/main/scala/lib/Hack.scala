package lib

import scala.collection.immutable.Seq

object Hack {
  import scala.meta._

  @scala.annotation.compileTimeOnly("scalaplayground.macros not expanded")
  class state extends scala.annotation.StaticAnnotation {
    inline def apply(defn: Any): Any = meta {
      defn match {
        case q"..$mods def $name ( ..$args ): $typ = { ..${lines: Seq[Term] } }" => transform(defn, mods, name, args, typ, lines)
        case q"..$mods def $name: $typ = { ..${lines: Seq[Term] } }" => transform(defn, mods, name, Seq.empty, typ, lines)
        case q"..$mods def $name ( ..$args ): $typ = ${line: Term}" => transform(defn, mods, name, args, typ, Seq(line))
        case q"..$mods def $name: $typ = ${line: Term}" => transform(defn, mods, name, Seq.empty, typ, Seq(line))
        case _ =>
          println(defn)
          abort("wrong state syntax")
      }
    }
  }
  private def transform(defn: Any, mods: Seq[Mod], name: Term.Name, args: Seq[Term.Param], typ: Option[Type], lines: Seq[Term]) = {

    lazy val argTypes = args.map(_.decltpe).map {
      case Some(targ"${tpe: Type}") => tpe
      case _ => abort("argument type is unknown")
    }

    args.length match {
      case 0 => q"..$mods val ${Pat.Var.Term(name)} : RegisteredFlowStateAux[Unit] = ref(scala.Symbol(${name.value}), handler(..$lines))"
      case 1 => q"..$mods val ${Pat.Var.Term(name)} : RegisteredFlowStateAux[..$argTypes] = ref(scala.Symbol(${name.value}), (${args.head}) => handler(..$lines))"
      case _ =>
        val varPattern = Pat.Tuple(args.map((x: Term.Param) =>
          Pat.Var.Term(Term.Name(x.name.value))
        ))
        q"..$mods val ${scala.meta.Pat.Var.Term(name)} : RegisteredFlowStateAux[(..$argTypes)] = ref(scala.Symbol(${name.value}), {case ($varPattern) => handler(..$lines) })"
    }
  }
}
