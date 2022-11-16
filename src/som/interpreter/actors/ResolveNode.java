package som.interpreter.actors;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;

import som.interpreter.SArguments;
import som.vm.VmSettings;
import tools.debugger.asyncstacktraces.ShadowStackEntry;


public abstract class ResolveNode extends AbstractPromiseResolutionNode {

  /**
   * Normal case, when the promise is resolved with a value that's not a promise.
   * Here we need to distinguish the explicit promises to ask directly to the promise
   * if a promise resolution breakpoint was set.
   */
  @Specialization(guards = {"notAPromise(result)"})
  public SPromise.SResolver normalResolution(final VirtualFrame frame,
      final SPromise.SResolver resolver, final Object result, final Object maybeEntry,
      final boolean haltOnResolver, final boolean haltOnResolution) {
    SPromise promise = resolver.getPromise();

    // this is needed to suspend on explicit promises (which resolved to a a value different
    // from another promise)
    if (haltOnResolver || promise.getHaltOnResolver()) {
      haltNode.executeEvaluated(frame, result);
    }

    ShadowStackEntry resolutionEntry = null;
    if (VmSettings.ACTOR_ASYNC_STACK_TRACE_STRUCTURE) {
      ShadowStackEntry entry = SArguments.getShadowStackEntry(frame.getArguments());
      assert !VmSettings.ACTOR_ASYNC_STACK_TRACE_STRUCTURE || entry != null;
      final ShadowStackEntry.EntryForPromiseResolution.ResolutionLocation location =
          ShadowStackEntry.EntryForPromiseResolution.ResolutionLocation.SUCCESSFUL;
      resolutionEntry =
          ShadowStackEntry.createAtPromiseResolution(entry, this.getParent(), location,
              ""); // "value: " + result.toString()
      SArguments.saveCausalEntryForPromise(maybeEntry, resolutionEntry);
    }

    resolvePromise(SPromise.Resolution.SUCCESSFUL, resolver, result, resolutionEntry,
        haltOnResolution || promise.getHaltOnResolution(), frame, this.getParent());
    return resolver;
  }
}
