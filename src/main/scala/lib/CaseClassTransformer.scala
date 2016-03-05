package lib

import shapeless.Generic

/**
 * Typeclass for transforming a case class to another case class with a different name.
 * The types should should be isomorphic.
 *
 * @tparam I The input type which should be isomorphic to type O
 * @tparam O The output type which should be isomorphic to type I
 */
@annotation.implicitNotFound("""Could not prove that ${I} can be converted to/from ${O}.""")
trait CaseClassTransformer[I, O] {
  def transform(cmd: I): O
}

object CaseClassTransformer {
  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.ExplicitImplicitTypes"))
  implicit def transform[I, O, IRepr, ORepr](implicit
    genericC: Generic.Aux[I, IRepr],
    genericE: Generic.Aux[O, ORepr],
    proof: IRepr =:= ORepr): CaseClassTransformer[I, O] = new CaseClassTransformer[I, O] {
    def transform(input: I): O = genericE.from(genericC.to(input))
  }
}

