package dotty.tools
package dotc
package cc

import core.*
import Types.*, Symbols.*, Contexts.*, Decorators.*
import util.{SimpleIdentitySet, Property}
import typer.ErrorReporting.Addenda
import util.common.alwaysTrue
import scala.collection.mutable
import CCState.*
import Periods.{NoRunId, RunWidth}
import compiletime.uninitialized
import StdNames.nme
import CaptureSet.VarState
import Annotations.Annotation
import config.Printers.capt

object CaptureRef:
  opaque type Validity = Int
  def validId(runId: Int, iterId: Int): Validity =
    runId + (iterId << RunWidth)
  def currentId(using Context): Validity = validId(ctx.runId, ccState.iterationId)
  val invalid: Validity = validId(NoRunId, 0)

/** A trait for references in CaptureSets. These can be NamedTypes, ThisTypes or ParamRefs,
 *  as well as three kinds of AnnotatedTypes representing readOnly, reach, and maybe capabilities.
 *  If there are several annotations they come with an order:
 *  `*` first, `.rd` next, `?` last.
 */
trait CaptureRef extends TypeProxy, ValueType:
  import CaptureRef.*

  private var myCaptureSet: CaptureSet | Null = uninitialized
  private var myCaptureSetValid: Validity = invalid
  private var mySingletonCaptureSet: CaptureSet.Const | Null = null
  private var myDerivedRefs: List[AnnotatedType] = Nil

  /** A derived reach, readOnly or maybe reference. Derived references are cached. */
  def derivedRef(annotCls: ClassSymbol)(using Context): AnnotatedType =
    def recur(refs: List[AnnotatedType]): AnnotatedType = refs match
      case ref :: refs1 =>
        if ref.annot.symbol == annotCls then ref else recur(refs1)
      case Nil =>
        val derived = AnnotatedType(this, Annotation(annotCls, util.Spans.NoSpan))
        myDerivedRefs = derived :: myDerivedRefs
        derived
    recur(myDerivedRefs)

  /** Is the reference tracked? This is true if it can be tracked and the capture
   *  set of the underlying type is not always empty.
   */
  final def isTracked(using Context): Boolean =
    this.isTrackableRef && (isRootCapability || !captureSetOfInfo.isAlwaysEmpty)

  /** Is this a maybe reference of the form `x?`? */
  final def isMaybe(using Context): Boolean = this ne stripMaybe

  /** Is this a read-only reference of the form `x.rd` or a capture set variable
   *  with only read-ony references in its upper bound?
   */
  final def isReadOnly(using Context): Boolean = this match
    case tp: TypeRef => tp.captureSetOfInfo.isReadOnly
    case _ => this ne stripReadOnly

  /** Is this a reach reference of the form `x*`? */
  final def isReach(using Context): Boolean = this ne stripReach

  final def stripMaybe(using Context): CaptureRef = this match
    case AnnotatedType(tp1: CaptureRef, annot) if annot.symbol == defn.MaybeCapabilityAnnot =>
      tp1
    case _ =>
      this

  final def stripReadOnly(using Context): CaptureRef = this match
    case tp @ AnnotatedType(tp1: CaptureRef, annot) =>
      val sym = annot.symbol
      if sym == defn.ReadOnlyCapabilityAnnot then
        tp1
      else if sym == defn.MaybeCapabilityAnnot then
        tp.derivedAnnotatedType(tp1.stripReadOnly, annot)
      else
        this
    case _ =>
      this

  final def stripReach(using Context): CaptureRef = this match
    case tp @ AnnotatedType(tp1: CaptureRef, annot) =>
      val sym = annot.symbol
      if sym == defn.ReachCapabilityAnnot then
        tp1
      else if sym == defn.ReadOnlyCapabilityAnnot || sym == defn.MaybeCapabilityAnnot then
        tp.derivedAnnotatedType(tp1.stripReach, annot)
      else
        this
    case _ =>
      this

  /** Is this reference the generic root capability `cap` ? */
  final def isCap(using Context): Boolean = this match
    case tp: TermRef => tp.name == nme.CAPTURE_ROOT && tp.symbol == defn.captureRoot
    case _ => false

  /** Is this reference a Fresh instance? */
  final def isFresh(using Context): Boolean = this match
    case root.Fresh(_) => true
    case _ => false

  /** Is this reference the generic root capability `cap` or a Fresh instance? */
  final def isCapOrFresh(using Context): Boolean = isCap || isFresh

  /** Is this reference one of the generic root capabilities `cap` or `cap.rd` ? */
  final def isRootCapability(using Context): Boolean = this match
    case ReadOnlyCapability(tp1) => tp1.isRootCapability
    case root(_) => true
    case _ => isCap

  /** An exclusive capability is a capability that derives
   *  indirectly from a maximal capability without going through
   *  a read-only capability first.
   */
  final def isExclusive(using Context): Boolean =
    !isReadOnly && (isRootCapability || captureSetOfInfo.isExclusive)

  // With the support of paths, we don't need to normalize the `TermRef`s anymore.
  // /** Normalize reference so that it can be compared with `eq` for equality */
  // final def normalizedRef(using Context): CaptureRef = this match
  //   case tp @ AnnotatedType(parent: CaptureRef, annot) if tp.isTrackableRef =>
  //     tp.derivedAnnotatedType(parent.normalizedRef, annot)
  //   case tp: TermRef if tp.isTrackableRef =>
  //     tp.symbol.termRef
  //   case _ => this

  /** The capture set consisting of exactly this reference */
  final def singletonCaptureSet(using Context): CaptureSet.Const =
    if mySingletonCaptureSet == null then
      mySingletonCaptureSet = CaptureSet(this)
    mySingletonCaptureSet.uncheckedNN

  /** The capture set of the type underlying this reference */
  final def captureSetOfInfo(using Context): CaptureSet =
    if myCaptureSetValid == currentId then myCaptureSet.nn
    else if myCaptureSet.asInstanceOf[AnyRef] eq CaptureSet.Pending then CaptureSet.empty
    else
      myCaptureSet = CaptureSet.Pending
      val computed = CaptureSet.ofInfo(this)
      if !isCaptureChecking
          || ctx.mode.is(Mode.IgnoreCaptures)
          || !underlying.exists
          || underlying.isProvisional
      then
        myCaptureSet = null
      else
        myCaptureSet = computed
        myCaptureSetValid = currentId
      computed

  final def invalidateCaches() =
    myCaptureSetValid = invalid

  /**  x subsumes x
   *   x =:= y       ==>  x subsumes y
   *   x subsumes y  ==>  x subsumes y.f
   *   x subsumes y  ==>  x* subsumes y, x subsumes y?
   *   x subsumes y  ==>  x* subsumes y*, x? subsumes y?
   *   x: x1.type /\ x1 subsumes y  ==>  x subsumes y
   *   X = CapSet^cx, exists rx in cx, rx subsumes y     ==>  X subsumes y
   *   Y = CapSet^cy, forall ry in cy, x subsumes ry     ==>  x subsumes Y
   *   X: CapSet^c1...CapSet^c2, (CapSet^c1) subsumes y  ==>  X subsumes y
   *   Y: CapSet^c1...CapSet^c2, x subsumes (CapSet^c2)  ==>  x subsumes Y
   *   Contains[X, y]  ==>  X subsumes y
   */
  final def subsumes(y: CaptureRef)(using ctx: Context)(using vs: VarState = VarState.Separate): Boolean =

    def subsumingRefs(x: Type, y: Type): Boolean = x match
      case x: CaptureRef => y match
        case y: CaptureRef => x.subsumes(y)
        case _ => false
      case _ => false

    def viaInfo(info: Type)(test: Type => Boolean): Boolean = info.dealias match
      case info: SingletonCaptureRef => test(info)
      case CapturingType(parent, _) =>
        if this.derivesFrom(defn.Caps_CapSet) then test(info)
          /*
            If `this` is a capture set variable `C^`, then it is possible that it can be
            reached from term variables in a reachability chain through the context.
            For instance, in `def test[C^](src: Foo^{C^}) = { val x: Foo^{src} = src; val y: Foo^{x} = x; y }`
            we expect that `C^` subsumes `x` and `y` in the body of the method
            (cf. test case cc-poly-varargs.scala for a more involved example).
          */
        else viaInfo(parent)(test)
      case info: AndType => viaInfo(info.tp1)(test) || viaInfo(info.tp2)(test)
      case info: OrType => viaInfo(info.tp1)(test) && viaInfo(info.tp2)(test)
      case _ => false

    (this eq y)
    || maxSubsumes(y, canAddHidden = !vs.isOpen)
    || y.match
        case y: TermRef if !y.isCap =>
            y.prefix.match
              case ypre: CaptureRef =>
                this.subsumes(ypre)
                || this.match
                    case x @ TermRef(xpre: CaptureRef, _) if x.symbol == y.symbol =>
                      // To show `{x.f} <:< {y.f}`, it is important to prove `x` and `y`
                      // are equvalent, which means `x =:= y` in terms of subtyping,
                      // not just `{x} =:= {y}` in terms of subcapturing.
                      // It is possible to construct two singleton types `x` and `y`,
                      // which subsume each other, but are not equal references.
                      // See `tests/neg-custom-args/captures/path-prefix.scala` for example.
                      withMode(Mode.IgnoreCaptures) {TypeComparer.isSameRef(xpre, ypre)}
                    case _ =>
                      false
              case _ => false
          || viaInfo(y.info)(subsumingRefs(this, _))
        case MaybeCapability(y1) => this.stripMaybe.subsumes(y1)
        case ReadOnlyCapability(y1) => this.stripReadOnly.subsumes(y1)
        case y: TypeRef if y.derivesFrom(defn.Caps_CapSet) =>
          // The upper and lower bounds don't have to be in the form of `CapSet^{...}`.
          // They can be other capture set variables, which are bounded by `CapSet`,
          // like `def test[X^, Y^, Z >: X <: Y]`.
          y.info match
            case TypeBounds(_, hi: CaptureRef) => this.subsumes(hi)
            case _ => y.captureSetOfInfo.elems.forall(this.subsumes)
        case CapturingType(parent, refs) if parent.derivesFrom(defn.Caps_CapSet) || this.derivesFrom(defn.Caps_CapSet) =>
          /* The second condition in the guard is for `this` being a `CapSet^{a,b...}` and etablishing a
             potential reachability chain through `y`'s capture to a binding with
             `this`'s capture set (cf. `CapturingType` case in `def viaInfo` above for more context).
           */
          refs.elems.forall(this.subsumes)
        case _ => false
    || this.match
        case ReachCapability(x1) => x1.subsumes(y.stripReach)
        case x: TermRef => viaInfo(x.info)(subsumingRefs(_, y))
        case x: TypeRef if assumedContainsOf(x).contains(y) => true
        case x: TypeRef if x.derivesFrom(defn.Caps_CapSet) =>
          x.info match
            case TypeBounds(lo: CaptureRef, _) =>
              lo.subsumes(y)
            case _ =>
              x.captureSetOfInfo.elems.exists(_.subsumes(y))
        case CapturingType(parent, refs) if parent.derivesFrom(defn.Caps_CapSet) =>
          refs.elems.exists(_.subsumes(y))
        case _ => false
  end subsumes

  /** This is a maximal capability that subsumes `y` in given context and VarState.
   *  @param canAddHidden  If true we allow maximal capabilities to subsume all other capabilities.
   *                       We add those capabilities to the hidden set if this is a Fresh instance.
   *                       If false we only accept `y` elements that are already in the
   *                       hidden set of this Fresh instance. The idea is that in a VarState that
   *                       accepts additions we first run `maxSubsumes` with `canAddHidden = false`
   *                       so that new variables get added to the sets. If that fails, we run
   *                       the test again with canAddHidden = true as a last effort before we
   *                       fail a comparison.
   */
  def maxSubsumes(y: CaptureRef, canAddHidden: Boolean)(using ctx: Context)(using vs: VarState = VarState.Separate): Boolean =
    def yIsExistential = y.stripReadOnly match
      case root.Result(_) =>
        capt.println(i"failed existential $this >: $y")
        true
      case _ => false
    (this eq y)
    || this.match
      case root.Fresh(hidden) =>
        vs.ifNotSeen(this)(hidden.elems.exists(_.subsumes(y)))
        || !y.stripReadOnly.isCap
            && !yIsExistential
            && !y.isInstanceOf[TermParamRef]
            && canAddHidden
            && vs.addHidden(hidden, y)
      case x @ root.Result(binder) =>
        val result = y match
          case y @ root.Result(_) => vs.unify(x, y)
          case _ => y.derivesFromSharedCapability
        if !result then
          ccState.addNote(CaptureSet.ExistentialSubsumesFailure(x, y))
        result
      case _ =>
        y match
          case ReadOnlyCapability(y1) => this.stripReadOnly.maxSubsumes(y1, canAddHidden)
          case _ if this.isCap =>
            y.isCap
            || y.derivesFromSharedCapability
            || !yIsExistential
                && canAddHidden
                && vs != VarState.HardSeparate
                && (CCState.capIsRoot
                    // || { println(i"no longer $this maxSubsumes $y, ${y.isCap}"); false } // debug
                   )
            || false
          case _ =>
            false

  /** `x covers y` if we should retain `y` when computing the overlap of
   *  two footprints which have `x` respectively `y` as elements.
   *  We assume that .rd have already been stripped on both sides.
   *  We have:
   *
   *   x covers x
   *   x covers y  ==>  x covers y.f
   *   x covers y  ==>  x* covers y*, x? covers y?
   *   TODO what other clauses from subsumes do we need to port here?
   */
  final def covers(y: CaptureRef)(using Context): Boolean =
    (this eq y)
    || y.match
        case y @ TermRef(ypre: CaptureRef, _) if !y.isCap =>
          this.covers(ypre)
        case ReachCapability(y1) =>
          this match
            case ReachCapability(x1) => x1.covers(y1)
            case _ => false
        case MaybeCapability(y1) =>
          this match
            case MaybeCapability(x1) => x1.covers(y1)
            case _ => false
        case root.Fresh(hidden) =>
          hidden.superCaps.exists(this covers _)
        case _ =>
          false

  def assumedContainsOf(x: TypeRef)(using Context): SimpleIdentitySet[CaptureRef] =
    CaptureSet.assumedContains.getOrElse(x, SimpleIdentitySet.empty)

end CaptureRef

trait SingletonCaptureRef extends SingletonType, CaptureRef

