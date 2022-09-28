package som.compiler;

import static som.interpreter.TruffleCompiler.transferToInterpreterAndInvalidate;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.SourceSection;

import bd.inlining.NodeState;
import bd.source.SourceCoordinate;
import som.VM;
import som.compiler.MixinBuilder.MixinDefinitionId;
import som.interpreter.nodes.ArgumentReadNode.LocalArgumentReadNode;
import som.interpreter.nodes.ArgumentReadNode.LocalSelfReadNode;
import som.interpreter.nodes.ArgumentReadNode.LocalSuperReadNode;
import som.interpreter.nodes.ArgumentReadNode.NonLocalArgumentReadNode;
import som.interpreter.nodes.ArgumentReadNode.NonLocalSelfReadNode;
import som.interpreter.nodes.ArgumentReadNode.NonLocalSuperReadNode;
import som.interpreter.nodes.ExpressionNode;
import som.interpreter.nodes.LocalVariableNodeFactory.LocalVariableReadNodeGen;
import som.interpreter.nodes.LocalVariableNodeFactory.LocalVariableWriteNodeGen;
import som.interpreter.nodes.NonLocalVariableNodeFactory.NonLocalVariableReadNodeGen;
import som.interpreter.nodes.NonLocalVariableNodeFactory.NonLocalVariableWriteNodeGen;
import som.vm.Symbols;
import som.vmobjects.SSymbol;


/**
 * Represents state belonging to a method activation.
 */
public abstract class Variable implements bd.inlining.Variable<ExpressionNode> {

  public static class AccessNodeState implements NodeState {
    private final MixinDefinitionId id;

    /* Only used for super reads. */
    private final boolean classSide;

    public AccessNodeState(final MixinDefinitionId id) {
      this(id, false);
    }

    public AccessNodeState(final MixinDefinitionId id, final boolean classSide) {
      this.id = id;
      this.classSide = classSide;
    }
  }

  public final SSymbol       name;
  public final SourceSection source;

  Variable(final SSymbol name, final SourceSection source) {
    this.name = name;
    this.source = source;
  }

  public void init(final FrameDescriptor frameDescriptor) {}

  /** Gets the name including lexical location. */
  public final SSymbol getQualifiedName() {
    return Symbols.symbolFor(name.getString() + SourceCoordinate.getLocationQualifier(source));
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + name.getString() + ")";
  }

  @Override
  public int hashCode() {
    if (source == null) {
      return name.hashCode();
    }
    return source.hashCode();
  }

  @Override
  public boolean equals(final Object o) {
    assert o != null;
    if (o == this) {
      return true;
    }
    if (!(o instanceof Variable)) {
      return false;
    }
    Variable var = (Variable) o;
    if (var.source == source) {
      assert name == var.name : "Defined in the same place, but names not equal?";
      return true;
    }
    assert source == null || !source.equals(
        var.source)
        : "Why are there multiple objects for this source section? might need to fix comparison above";
    return false;
  }

  public abstract Variable split();

  public abstract Local splitToMergeIntoOuterScope(int newSlotIndex);

  /** Access method for the debugger and tools. Not to be used in language. */
  public abstract Object read(Frame frame);

  /** Not meant to be shown in debugger or other tools. */
  public boolean isInternal() {
    return false;
  }

  /**
   * Represents a parameter (argument) to a method activation.
   * Arguments are stored in the arguments array in a {@link Frame}.
   */
  public static final class Argument extends Variable {
    public final int index;

    Argument(final SSymbol name, final int index, final SourceSection source) {
      super(name, source);
      this.index = index;
    }

    public boolean isSelf() {
      return Symbols.SELF == name || Symbols.BLOCK_SELF == name;
    }

    @Override
    public ExpressionNode getThisReadNode(final int contextLevel, final NodeState state,
        final SourceSection source) {
      assert this instanceof Argument;
      AccessNodeState holder = (AccessNodeState) state;

      if (contextLevel == 0) {
        return new LocalSelfReadNode(this, holder.id).initialize(source);
      } else {
        return new NonLocalSelfReadNode(this, holder.id, contextLevel).initialize(source);
      }
    }

    @Override
    public ExpressionNode getSuperReadNode(final int contextLevel, final NodeState state,
        final SourceSection source) {
      assert this instanceof Argument;
      AccessNodeState holder = (AccessNodeState) state;

      if (contextLevel == 0) {
        return new LocalSuperReadNode(this, holder.id, holder.classSide).initialize(source);
      } else {
        return new NonLocalSuperReadNode(this, contextLevel, holder.id,
            holder.classSide).initialize(source);
      }
    }

    @Override
    public Variable split() {
      return this;
    }

    @Override
    public Local splitToMergeIntoOuterScope(final int newSlotIndex) {
      if (isSelf()) {
        return null;
      }

      return new ImmutableLocal(name, source, newSlotIndex);
    }

    @Override
    public ExpressionNode getReadNode(final int contextLevel,
        final SourceSection source) {
      transferToInterpreterAndInvalidate("Variable.getReadNode");
      if (contextLevel == 0) {
        return new LocalArgumentReadNode(this).initialize(source);
      } else {
        return new NonLocalArgumentReadNode(this, contextLevel).initialize(source);
      }
    }

    @Override
    public Object read(final Frame frame) {
      VM.callerNeedsToBeOptimized("Not to be used outside of tools");
      return frame.getArguments()[index];
    }
  }

  /**
   * Represents a local variable, i.e., local to a specific scope.
   * Locals are stored in {@link FrameSlot}s inside a {@link Frame}.
   */
  public abstract static class Local extends Variable {
    protected final int slotIndex;

    @CompilationFinal private FrameDescriptor frameDescriptor;

    Local(final SSymbol name, final SourceSection source, final int slotIndex) {
      super(name, source);
      this.slotIndex = slotIndex;
    }

    @Override
    public void init(final FrameDescriptor descriptor) {
      this.frameDescriptor = descriptor;
    }

    @Override
    public ExpressionNode getReadNode(final int contextLevel, final SourceSection source) {
      transferToInterpreterAndInvalidate("Variable.getReadNode");
      ExpressionNode node;
      if (contextLevel == 0) {
        node = LocalVariableReadNodeGen.create(this);
      } else {
        node = NonLocalVariableReadNodeGen.create(contextLevel, this);
      }
      node.initialize(source);
      return node;
    }

    public int getSlotIndex() {
      return slotIndex;
    }

    protected abstract Local create(int slotIndex);

    public abstract boolean isMutable();

    @Override
    public Local split() {
      return create(slotIndex);
    }

    @Override
    public ExpressionNode getWriteNode(final int contextLevel, final ExpressionNode valueExpr,
        final SourceSection source) {
      transferToInterpreterAndInvalidate("Variable.getWriteNode");
      ExpressionNode node;
      if (contextLevel == 0) {
        node = LocalVariableWriteNodeGen.create(this, valueExpr);
      } else {
        node = NonLocalVariableWriteNodeGen.create(contextLevel, this,
            valueExpr);
      }
      node.initialize(source);
      return node;
    }

    @Override
    public Local splitToMergeIntoOuterScope(final int newSlotIndex) {
      return create(newSlotIndex);
    }

    @Override
    public Object read(final Frame frame) {
      VM.callerNeedsToBeOptimized("Not to be used outside of tools");
      return frame.getValue(slotIndex);
    }

    public FrameDescriptor getFrameDescriptor() {
      assert frameDescriptor != null : "Local was not initialized with frame descriptor.";
      return frameDescriptor;
    }
  }

  public static final class MutableLocal extends Local {
    MutableLocal(final SSymbol name, final SourceSection source, final int slotIndex) {
      super(name, source, slotIndex);
    }

    @Override
    public Local create(final int slotIndex) {
      return new MutableLocal(name, source, slotIndex);
    }

    @Override
    public boolean isMutable() {
      return true;
    }
  }

  public static final class ImmutableLocal extends Local {
    ImmutableLocal(final SSymbol name, final SourceSection source, final int slotIndex) {
      super(name, source, slotIndex);
    }

    @Override
    public Local create(final int slotIndex) {
      return new ImmutableLocal(name, source, slotIndex);
    }

    @Override
    public boolean isMutable() {
      return false;
    }
  }

  /**
   * Represents a variable that is used for internal purposes only.
   * Does not hold language-level values, and thus, is ignored in the debugger.
   * `Internals` are stored in {@link FrameSlot}s.
   */
  public static final class Internal extends Local {

    public Internal(final SSymbol name, final int slotIndex) {
      super(name, null, slotIndex);
    }

    @Override
    public boolean isMutable() {
      return false;
    }

    @Override
    public int getSlotIndex() {
      return slotIndex;
    }

    @Override
    // TODO: we need to sort this out with issue #240, and decide what we want here
    public Local split() {
      Internal newInternal = new Internal(name, slotIndex);
      return newInternal;
    }

    @Override
    public Local create(final int slotIndex) {
      return new Internal(name, slotIndex);
    }

    @Override
    public ExpressionNode getReadNode(final int contextLevel, final SourceSection source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Local splitToMergeIntoOuterScope(final int newSlotIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object read(final Frame frame) {
      throw new UnsupportedOperationException(
          "This is for reading language-level values. Think, we should not need this");
    }

    @Override
    public boolean isInternal() {
      return true;
    }
  }
}
