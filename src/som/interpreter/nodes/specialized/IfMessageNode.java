package som.interpreter.nodes.specialized;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.frame.VirtualFrame;

import bd.primitives.Primitive;
import som.interpreter.SArguments;
import som.interpreter.nodes.nary.BinaryComplexOperation;
import som.vm.VmSettings;
import som.vm.constants.Nil;
import som.vmobjects.SBlock;
import som.vmobjects.SInvokable;


public abstract class IfMessageNode extends BinaryComplexOperation {

  @GenerateNodeFactory
  @Primitive(selector = "ifTrue:", noWrapper = true)
  public abstract static class IfTrueMessageNode extends IfMessageNode {
    public IfTrueMessageNode() {
      super(true);
    }
  }

  @GenerateNodeFactory
  @Primitive(selector = "ifFalse:", noWrapper = true)
  public abstract static class IfFalseMessageNode extends IfMessageNode {
    public IfFalseMessageNode() {
      super(false);
    }
  }

  protected final ConditionProfile condProf = ConditionProfile.createCountingProfile();
  private final boolean            expected;

  protected IfMessageNode(final boolean expected) {
    this.expected = expected;
  }

  protected static DirectCallNode createDirect(final SInvokable method) {
    return Truffle.getRuntime().createDirectCallNode(method.getCallTarget());
  }

  protected static IndirectCallNode createIndirect() {
    return Truffle.getRuntime().createIndirectCallNode();
  }

  @Specialization(guards = {"arg.getMethod() == method"})
  public final Object cachedBlock(VirtualFrame frame, final boolean rcvr, final SBlock arg,
      @Cached("arg.getMethod()") final SInvokable method,
      @Cached("createDirect(method)") final DirectCallNode callTarget) {
    Object[] args;
    if (VmSettings.ACTOR_ASYNC_STACK_TRACE_STRUCTURE) {
      args = new Object[] {arg, SArguments.getShadowStackEntry(frame)};
    } else {
      args = new Object[] {arg};
    }
    // this was there before Async Stack traces
    if (condProf.profile(rcvr == expected)) {
      return callTarget.call(args);
    } else {
      return Nil.nilObject;
    }

  }

  @Specialization(replaces = "cachedBlock")
  public final Object fallback(final boolean rcvr, final SBlock arg,
      @Cached("createIndirect()") final IndirectCallNode callNode) {
    if (condProf.profile(rcvr == expected)) {
      return callNode.call(arg.getMethod().getCallTarget(), new Object[] {arg});
    } else {
      return Nil.nilObject;
    }
  }

  protected final boolean notABlock(final Object arg) {
    return !(arg instanceof SBlock);
  }

  @Specialization(guards = {"notABlock(arg)"})
  public final Object literal(final boolean rcvr, final Object arg) {
    if (condProf.profile(rcvr == expected)) {
      return arg;
    } else {
      return Nil.nilObject;
    }
  }
}
