package som.interpreter.nodes.specialized;

import som.interpreter.Arguments;
import som.interpreter.nodes.BinaryMessageNode;
import som.interpreter.nodes.UnaryMessageNode;
import som.interpreter.nodes.messages.UnarySendNode.InlinableUnarySendNode;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SBlock;
import som.vmobjects.SMethod;
import som.vmobjects.SObject;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.LoopCountReceiver;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;


public abstract class WhileTrueMessageNode extends BinaryMessageNode {
  private final SMethod conditionMethod;
  private final SMethod bodyMethod;
  @Child protected UnaryMessageNode conditionValueSend;
  @Child protected UnaryMessageNode bodyValueSend;

  public WhileTrueMessageNode(final BinaryMessageNode node, final Object rcvr,
      final Object arg) {
    super(node);
    if (rcvr instanceof SBlock) {
      SBlock rcvrBlock = (SBlock) rcvr;
      conditionMethod = rcvrBlock.getMethod();
      conditionValueSend = adoptChild(createInlineableNode(conditionMethod));
    } else {
      conditionMethod = null;
    }

    if (arg instanceof SBlock) {
      SBlock argBlock = (SBlock) arg;
      bodyMethod = argBlock.getMethod();
      bodyValueSend = adoptChild(createInlineableNode(bodyMethod));
      bodyValueSend.assignSourceSection(bodyMethod.getTruffleInvokable().getSourceSection());
    } else {
      bodyMethod = null;
    }
  }
  public WhileTrueMessageNode(final WhileTrueMessageNode node) {
    super(node);
    conditionMethod = node.conditionMethod;
    if (node.conditionMethod != null) {
      conditionValueSend = adoptChild(createInlineableNode(conditionMethod));
    }

    bodyMethod = node.bodyMethod;
    if (node.bodyMethod != null) {
      bodyValueSend = adoptChild(createInlineableNode(bodyMethod));
    }
  }

  protected boolean isSameArgument(final Object receiver, final Object argument) {
    return (this.conditionMethod == null || ((SBlock) receiver).getMethod() == conditionMethod)
        && (this.bodyMethod      == null || ((SBlock) argument).getMethod() == bodyMethod);
  }

  private InlinableUnarySendNode createInlineableNode(final SMethod method) {
    return new InlinableUnarySendNode(method.getSignature(),
        universe, method.getCallTarget(), method.getTruffleInvokable());
  }

  private SBlock createBlock(final SBlock block) {
    SMethod   method  = block.getMethod();
    Arguments context = block.getContext(); // TODO: test whether the current implementation is correct, or whether it should be the following: Method.getUpvalues(frame);
    return universe.newBlock(method, context);
  }

  protected boolean receiverIsTrueObject(final SObject receiver) {
    return receiver == universe.trueObject;
  }

  @Specialization(guards = "isSameArgument")
  public SAbstractObject doWhileTrue(final VirtualFrame frame,
      final SBlock loopCondition, final SBlock loopBody) {
    int iterationCount = 0;

    Object loopConditionResult = conditionValueSend.executeEvaluated(frame, createBlock(loopCondition));

    try {
      // TODO: this is a simplification, we don't cover the case receiver isn't a boolean
      while (loopConditionResult == universe.trueObject) {
        bodyValueSend.executeEvaluated(frame, createBlock(loopBody));
        loopConditionResult = conditionValueSend.executeEvaluated(frame, createBlock(loopCondition));

        if (CompilerDirectives.inInterpreter()) {
          iterationCount++;
        }
      }
    } finally {
      if (CompilerDirectives.inInterpreter()) {
        reportLoopCount(iterationCount);
      }
    }

    return universe.nilObject;
  }

  @Specialization(guards = {"receiverIsTrueObject", "isSameArgument"})
  public SAbstractObject doWhileTrue(final VirtualFrame frame,
      final SObject loopCondition, final SBlock loopBody) {
    while (true) {
      bodyValueSend.executeEvaluated(frame, createBlock(loopBody));
    }
  }

  protected final void reportLoopCount(final int count) {
    CompilerAsserts.neverPartOfCompilation();
    Node current = getParent();
    while (current != null && !(current instanceof RootNode)) {
      current = current.getParent();
    }
    if (current != null) {
      RootNode root = (RootNode) current;
      if (root.getCallTarget() instanceof LoopCountReceiver) {
        ((LoopCountReceiver) root.getCallTarget()).reportLoopCount(count);
      }
    }
  }
}
