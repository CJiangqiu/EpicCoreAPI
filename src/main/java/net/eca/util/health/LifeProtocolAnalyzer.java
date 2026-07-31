package net.eca.util.health;

import net.eca.util.health.internal.ProtocolDataFlowEngine;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer;
import net.eca.util.health.protocol.AccessPath;
import net.eca.util.health.protocol.CandidateState;
import net.eca.util.health.protocol.LifeProtocol;
import net.eca.util.health.protocol.MethodReference;
import net.eca.util.health.protocol.ProtocolEvidence;
import net.eca.util.health.protocol.SemanticEndpoint;
import net.eca.util.health.protocol.StateLocation;
import net.eca.util.health.protocol.ValueExpression;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LifeProtocolAnalyzer {
    private final Map<Class<?>, AnalyzedClass> analyzedClasses = new ConcurrentHashMap<>();

    public AnalysisResult analyze(Class<?> entityClass) {
        if (entityClass == null) {
            return new AnalysisResult(AnalysisResult.Status.FAILED,
                    List.of(), Optional.empty(), "entity class is null");
        }
        return analyzedClasses.computeIfAbsent(entityClass, this::analyzeClass).result();
    }

    public ProtocolDataflowAnalyzer.AnalysisResult dataflowTree(Class<?> entityClass) {
        if (entityClass == null) return ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED;
        return analyzedClasses.computeIfAbsent(entityClass, this::analyzeClass).dataflowTree();
    }

    public ProtocolDataflowAnalyzer.ProtocolGraphResult protocolGraph(Class<?> entityClass) {
        if (entityClass == null) {
            return new ProtocolDataflowAnalyzer.ProtocolGraphResult(
                    ProtocolDataflowAnalyzer.AnalysisResult.EMPTY, List.of(), List.of(), List.of());
        }
        return analyzedClasses.computeIfAbsent(entityClass, this::analyzeClass).protocolGraph();
    }

    public void clear() {
        analyzedClasses.clear();
    }

    public void invalidate(Class<?> entityClass) {
        if (entityClass != null) analyzedClasses.remove(entityClass);
    }

    private AnalyzedClass analyzeClass(Class<?> entityClass) {
        try {
            ProtocolDataFlowEngine.init();
            ProtocolDataflowAnalyzer.ProtocolGraphResult graph =
                    ProtocolDataflowAnalyzer.analyzeProtocolGraph(entityClass);
            ProtocolDataflowAnalyzer.AnalysisResult result = graph.observation();
            if (result == null || result == ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED
                    || !isEligible(result)) {
                return new AnalyzedClass(ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED,
                        graph,
                        new AnalysisResult(AnalysisResult.Status.UNSUPPORTED, List.of(), Optional.empty(),
                                "no writable state reached the health observation"));
            }
            List<CandidateState> candidates = createCandidates(entityClass, graph);
            String diagnostic = graph.hasMaintenanceWriter()
                    ? "health authority participates in a maintenance write graph"
                    : "health authority has no discovered maintenance writer";
            AnalysisResult analysis = new AnalysisResult(AnalysisResult.Status.AMBIGUOUS,
                    candidates, Optional.empty(), diagnostic);
            return new AnalyzedClass(result, graph, analysis);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError error) throw error;
            AnalysisResult analysis = new AnalysisResult(AnalysisResult.Status.FAILED,
                    List.of(), Optional.empty(), t.getClass().getName() + ": " + t.getMessage());
            return new AnalyzedClass(ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED,
                    new ProtocolDataflowAnalyzer.ProtocolGraphResult(
                            ProtocolDataflowAnalyzer.AnalysisResult.EMPTY,
                            List.of(), List.of(), List.of()),
                    analysis);
        }
    }

    private static boolean isEligible(ProtocolDataflowAnalyzer.AnalysisResult result) {
        ProtocolDataflowAnalyzer.AnalysisResult.Kind kind = result.classify();
        return kind == ProtocolDataflowAnalyzer.AnalysisResult.Kind.DATAFLOW
                || (kind == ProtocolDataflowAnalyzer.AnalysisResult.Kind.CONST_OVERRIDE
                && !result.sources.isEmpty());
    }

    private static List<CandidateState> createCandidates(
            Class<?> entityClass, ProtocolDataflowAnalyzer.ProtocolGraphResult graph) {
        String entityInternal = entityClass.getName().replace('.', '/');
        AccessPath entityPath = AccessPath.entity(entityInternal);
        MethodReference healthReader = new MethodReference(entityInternal, "getHealth", "()F",
                MethodReference.InvocationKind.VIRTUAL);
        MethodReference mutationWriter = new MethodReference(entityInternal, "setHealth", "(F)V",
                MethodReference.InvocationKind.VIRTUAL);
        SemanticEndpoint readEndpoint = new SemanticEndpoint(
                SemanticEndpoint.Kind.HEALTH_OBSERVATION, healthReader);
        SemanticEndpoint writeEndpoint = new SemanticEndpoint(
                SemanticEndpoint.Kind.HEALTH_MUTATION, mutationWriter);
        List<CandidateState> candidates = new ArrayList<>();
        List<ProtocolDataflowAnalyzer.Source> sources = graph.dependencySources();
        for (int index = 0; index < sources.size(); index++) {
            ProtocolDataflowAnalyzer.Source source = sources.get(index);
            StateLocation location = toStateLocation(entityInternal, entityPath, source, index);
            boolean observationSource = graph.authoritySources().contains(source);
            boolean maintainedSource = graph.maintenanceWrites().stream()
                    .anyMatch(write -> write.sink().equals(source));
            List<ProtocolEvidence> evidence = new ArrayList<>();
            if (observationSource) {
                evidence.add(new ProtocolEvidence(location, readEndpoint, ProtocolEvidence.Kind.READ_DEPENDENCY,
                            ProtocolEvidence.Direction.BACKWARD_SLICE, ProtocolEvidence.Strength.REQUIRED,
                            new ProtocolEvidence.Provenance(healthReader, -1),
                            "Backward slicing reached " + source.label + "."));
            }
            evidence.add(new ProtocolEvidence(location, writeEndpoint, ProtocolEvidence.Kind.WRITE_PROPAGATION,
                            ProtocolEvidence.Direction.FORWARD_PROPAGATION,
                            maintainedSource || observationSource
                                    ? ProtocolEvidence.Strength.REQUIRED
                                    : ProtocolEvidence.Strength.SUPPORTING,
                            new ProtocolEvidence.Provenance(mutationWriter, -1),
                            maintainedSource
                                    ? "A lifecycle writer propagates through this state."
                                    : "The state belongs to the causal transaction closure."));
            candidates.add(new CandidateState(location, evidence));
        }
        return List.copyOf(candidates);
    }

    private static StateLocation toStateLocation(String entityInternal, AccessPath entityPath,
                                                 ProtocolDataflowAnalyzer.Source source, int index) {
        String descriptor = Type.getDescriptor(source.valueType);
        if (source instanceof ProtocolDataflowAnalyzer.SynchedDataSource synched) {
            return new StateLocation.SynchedDataState(entityPath, entityInternal, null,
                    synched.accessor.getId(), descriptor);
        }
        if (source instanceof ProtocolDataflowAnalyzer.FieldChainSource fieldChain
                && !fieldChain.chain.isEmpty()) {
            AccessPath receiver = entityPath;
            for (int stepIndex = 0; stepIndex < fieldChain.chain.size() - 1; stepIndex++) {
                ProtocolDataflowAnalyzer.FieldStep step = fieldChain.chain.get(stepIndex);
                receiver = receiver.append(new AccessPath.FieldStep(
                        step.ownerInternal(), step.name(), step.desc(), false));
            }
            ProtocolDataflowAnalyzer.FieldStep field = fieldChain.chain.get(fieldChain.chain.size() - 1);
            return new StateLocation.FieldState(receiver, field.ownerInternal(), field.name(), field.desc(), false);
        }
        if (source instanceof ProtocolDataflowAnalyzer.StaticFieldSource staticField) {
            String owner = staticField.field.getDeclaringClass().getName().replace('.', '/');
            AccessPath staticPath = new AccessPath(
                    new AccessPath.Root(AccessPath.RootKind.STATIC, owner, -1), List.of());
            return new StateLocation.FieldState(staticPath, owner, staticField.field.getName(),
                    Type.getDescriptor(staticField.field.getType()), true);
        }
        if (source instanceof ProtocolDataflowAnalyzer.NbtValueSource nbtValue) {
            Object key = nbtValue.keyExpr instanceof ProtocolDataflowAnalyzer.Reference reference
                    ? reference.value() : nbtValue.keyExpr.toString();
            return new StateLocation.NbtState(entityPath,
                    new ValueExpression.Constant(key, "Ljava/lang/String;"), descriptor);
        }
        MethodReference sourceReader = new MethodReference(entityInternal, "readCandidate" + index,
                "()" + descriptor, MethodReference.InvocationKind.VIRTUAL);
        return new StateLocation.MethodState(entityPath, sourceReader, null, descriptor);
    }

    private record AnalyzedClass(ProtocolDataflowAnalyzer.AnalysisResult dataflowTree,
                                 ProtocolDataflowAnalyzer.ProtocolGraphResult protocolGraph,
                                 AnalysisResult result) {
    }

    public record AnalysisResult(Status status, List<CandidateState> candidates,
                                 Optional<LifeProtocol> protocol, String diagnostic) {
        public AnalysisResult {
            Objects.requireNonNull(status, "status");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            protocol = Objects.requireNonNull(protocol, "protocol");
            if (status == Status.RESOLVED && protocol.isEmpty()) {
                throw new IllegalArgumentException("resolved analysis requires a protocol");
            }
            if (status != Status.RESOLVED && protocol.isPresent()) {
                throw new IllegalArgumentException("only resolved analysis may expose a protocol");
            }
            if (diagnostic == null) diagnostic = "";
        }

        public enum Status {
            RESOLVED,
            AMBIGUOUS,
            UNSUPPORTED,
            FAILED
        }
    }
}
