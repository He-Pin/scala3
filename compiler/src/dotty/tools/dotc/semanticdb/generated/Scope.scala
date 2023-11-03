// Generated by https://github.com/tanishiking/semanticdb-for-scala3
// Generated by the Scala Plugin for the Protocol Buffer Compiler.
// Do not edit!
//
// Protofile syntax: PROTO3

package dotty.tools.dotc.semanticdb
import dotty.tools.dotc.semanticdb.internal.*
import scala.annotation.internal.sharable

@SerialVersionUID(0L)
final case class Scope(
    symlinks: _root_.scala.Seq[_root_.scala.Predef.String] = _root_.scala.Seq.empty,
    hardlinks: _root_.scala.Seq[dotty.tools.dotc.semanticdb.SymbolInformation] = _root_.scala.Seq.empty
    )  extends SemanticdbGeneratedMessage  derives CanEqual {
    @transient @sharable
    private[this] var __serializedSizeMemoized: _root_.scala.Int = 0
    private[this] def __computeSerializedSize(): _root_.scala.Int = {
      var __size = 0
      symlinks.foreach { __item =>
        val __value = __item
        __size += SemanticdbOutputStream.computeStringSize(1, __value)
      }
      hardlinks.foreach { __item =>
        val __value = __item
        __size += 1 + SemanticdbOutputStream.computeUInt32SizeNoTag(__value.serializedSize) + __value.serializedSize
      }
      __size
    }
    override def serializedSize: _root_.scala.Int = {
      var __size = __serializedSizeMemoized
      if (__size == 0) {
        __size = __computeSerializedSize() + 1
        __serializedSizeMemoized = __size
      }
      __size - 1

    }
    def writeTo(`_output__`: SemanticdbOutputStream): _root_.scala.Unit = {
      symlinks.foreach { __v =>
        val __m = __v
        _output__.writeString(1, __m)
      };
      hardlinks.foreach { __v =>
        val __m = __v
        _output__.writeTag(2, 2)
        _output__.writeUInt32NoTag(__m.serializedSize)
        __m.writeTo(_output__)
      };
    }
    def clearSymlinks = copy(symlinks = _root_.scala.Seq.empty)
    def addSymlinks(__vs: _root_.scala.Predef.String *): Scope = addAllSymlinks(__vs)
    def addAllSymlinks(__vs: Iterable[_root_.scala.Predef.String]): Scope = copy(symlinks = symlinks ++ __vs)
    def withSymlinks(__v: _root_.scala.Seq[_root_.scala.Predef.String]): Scope = copy(symlinks = __v)
    def clearHardlinks = copy(hardlinks = _root_.scala.Seq.empty)
    def addHardlinks(__vs: dotty.tools.dotc.semanticdb.SymbolInformation *): Scope = addAllHardlinks(__vs)
    def addAllHardlinks(__vs: Iterable[dotty.tools.dotc.semanticdb.SymbolInformation]): Scope = copy(hardlinks = hardlinks ++ __vs)
    def withHardlinks(__v: _root_.scala.Seq[dotty.tools.dotc.semanticdb.SymbolInformation]): Scope = copy(hardlinks = __v)




    // @@protoc_insertion_point(GeneratedMessage[dotty.tools.dotc.semanticdb.Scope])
}

object Scope  extends SemanticdbGeneratedMessageCompanion[dotty.tools.dotc.semanticdb.Scope] {
  implicit def messageCompanion: SemanticdbGeneratedMessageCompanion[dotty.tools.dotc.semanticdb.Scope] = this
  def parseFrom(`_input__`: SemanticdbInputStream): dotty.tools.dotc.semanticdb.Scope = {
    val __symlinks: _root_.scala.collection.immutable.VectorBuilder[_root_.scala.Predef.String] = new _root_.scala.collection.immutable.VectorBuilder[_root_.scala.Predef.String]
    val __hardlinks: _root_.scala.collection.immutable.VectorBuilder[dotty.tools.dotc.semanticdb.SymbolInformation] = new _root_.scala.collection.immutable.VectorBuilder[dotty.tools.dotc.semanticdb.SymbolInformation]
    var _done__ = false
    while (!_done__) {
      val _tag__ = _input__.readTag()
      _tag__ match {
        case 0 => _done__ = true
        case 10 =>
          __symlinks += _input__.readStringRequireUtf8()
        case 18 =>
          __hardlinks += LiteParser.readMessage[dotty.tools.dotc.semanticdb.SymbolInformation](_input__)
        case tag => _input__.skipField(tag)
      }
    }
    dotty.tools.dotc.semanticdb.Scope(
        symlinks = __symlinks.result(),
        hardlinks = __hardlinks.result()
    )
  }






  lazy val defaultInstance = dotty.tools.dotc.semanticdb.Scope(
    symlinks = _root_.scala.Seq.empty,
    hardlinks = _root_.scala.Seq.empty
  )
  final val SYMLINKS_FIELD_NUMBER = 1
  final val HARDLINKS_FIELD_NUMBER = 2
  def of(
    symlinks: _root_.scala.Seq[_root_.scala.Predef.String],
    hardlinks: _root_.scala.Seq[dotty.tools.dotc.semanticdb.SymbolInformation]
  ): _root_.dotty.tools.dotc.semanticdb.Scope = _root_.dotty.tools.dotc.semanticdb.Scope(
    symlinks,
    hardlinks
  )
  // @@protoc_insertion_point(GeneratedMessageCompanion[dotty.tools.dotc.semanticdb.Scope])
}
