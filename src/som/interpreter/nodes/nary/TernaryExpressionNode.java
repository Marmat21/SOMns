package som.interpreter.nodes.nary;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.ProbeNode;

import bd.primitives.nodes.WithContext;
import som.VM;
import som.interpreter.nodes.ExpressionNode;
import som.vmobjects.SSymbol;
import tools.debugger.asyncstacktraces.ShadowStackEntry;


@NodeChildren({
    @NodeChild(value = "receiver", type = ExpressionNode.class),
    @NodeChild(value = "firstArg", type = ExpressionNode.class),
    @NodeChild(value = "secondArg", type = ExpressionNode.class)})
@GenerateWrapper
public abstract class TernaryExpressionNode extends EagerlySpecializableNode {

  protected ShadowStackEntry maybeEntry;

  protected TernaryExpressionNode() {}

  protected TernaryExpressionNode(final TernaryExpressionNode wrappedNode) {}

  public abstract Object executeEvaluated(VirtualFrame frame, Object receiver,
      Object firstArg, Object secondArg);

  @Override
  public WrapperNode createWrapper(final ProbeNode probe) {
    return new TernaryExpressionNodeWrapper(this, probe);
  }

  @Override
  public final Object doPreEvaluated(final VirtualFrame frame,
      final Object[] arguments) {
    return executeEvaluated(frame, arguments[0], arguments[1], arguments[2]);
  }

  @Override
  public EagerPrimitiveNode wrapInEagerWrapper(final SSymbol selector,
      final ExpressionNode[] arguments, final VM vm) {
    EagerTernaryPrimitiveNode result = new EagerTernaryPrimitiveNode(selector,
        arguments[0], arguments[1], arguments[2], this);
    result.initialize(sourceSection);
    return result;
  }

  public abstract static class TernarySystemOperation extends TernaryExpressionNode
      implements WithContext<TernaryExpressionNode, VM> {
    @CompilationFinal protected VM vm;

    @Override
    public TernaryExpressionNode initialize(final VM vm) {
      assert this.vm == null && vm != null;
      this.vm = vm;
      return this;
    }
  }
}
