package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // milliseconds; 10 minutes
    private static final int GROUP_SIZE = 10; // Tamanho de cada grupo de pedidos

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;

    public ChallengeSolver(
            List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        int nOrders = orders.size();
        int nAisles = aisles.size();
        int nGroups = (nOrders + GROUP_SIZE - 1) / GROUP_SIZE;

        // Melhor solução encontrada
        AtomicReference<ChallengeSolution> bestSolution = new AtomicReference<>(null);
        AtomicReference<Double> bestRatio = new AtomicReference<>(0.0);

        // Pré-processamento: total de unidades por pedido
        int[] totalUnits = new int[nOrders];
        for (int o = 0; o < nOrders; o++) {
            totalUnits[o] = orders.get(o).values().stream().mapToInt(Integer::intValue).sum();
        }

        // Mapeamento de itens para pedidos e corredores
        Map<Integer, List<int[]>> itemToOrders = new HashMap<>();
        Map<Integer, List<int[]>> itemToAisles = new HashMap<>();
        for (int i = 0; i < nItems; i++) {
            itemToOrders.put(i, new ArrayList<>());
            itemToAisles.put(i, new ArrayList<>());
        }
        for (int o = 0; o < nOrders; o++) {
            for (Map.Entry<Integer, Integer> entry : orders.get(o).entrySet()) {
                itemToOrders.get(entry.getKey()).add(new int[]{o, entry.getValue()});
            }
        }
        for (int a = 0; a < nAisles; a++) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(a).entrySet()) {
                itemToAisles.get(entry.getKey()).add(new int[]{a, entry.getValue()});
            }
        }

        // Executor para paralelismo
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        try {
            // Iterar sobre os grupos
            for (int g = 0; g < nGroups; g++) {
                if (getRemainingTime(stopWatch) <= 0) break;

                int startGroup = g * GROUP_SIZE;
                int endGroup = Math.min((g + 1) * GROUP_SIZE, nOrders);

                // Criar modelo CPLEX
                IloCplex cplex = new IloCplex();
                cplex.setParam(IloCplex.Param.Output.WriteLevel, 0); // Desativar logs
                cplex.setParam(IloCplex.Param.TimeLimit, getRemainingTime(stopWatch) / 1000.0);

                // Variáveis
                IloNumVar[] x = new IloNumVar[nOrders];
                IloIntVar[] y = cplex.boolVarArray(nAisles);
                for (int o = 0; o < nOrders; o++) {
                    if (o < startGroup) {
                        // Grupos anteriores: fixados como binários (valor inicial 0, será ajustado após)
                        x[o] = cplex.boolVar();
                    } else {
                        // Grupo atual e posteriores: relaxados
                        x[o] = cplex.numVar(0, 1);
                    }
                }

                // Restrições de tamanho da wave
                IloLinearNumExpr waveSizeExpr = cplex.linearNumExpr();
                for (int o = 0; o < nOrders; o++) {
                    waveSizeExpr.addTerm(totalUnits[o], x[o]);
                }
                cplex.addGe(waveSizeExpr, waveSizeLB, "WaveLB");
                cplex.addLe(waveSizeExpr, waveSizeUB, "WaveUB");

                // Restrições de disponibilidade
                for (int i = 0; i < nItems; i++) {
                    if (!itemToOrders.get(i).isEmpty()) {
                        IloLinearNumExpr demand = cplex.linearNumExpr();
                        IloLinearNumExpr supply = cplex.linearNumExpr();
                        for (int[] order : itemToOrders.get(i)) {
                            demand.addTerm(order[1], x[order[0]]);
                        }
                        for (int[] aisle : itemToAisles.get(i)) {
                            supply.addTerm(aisle[1], y[aisle[0]]);
                        }
                        cplex.addLe(demand, supply, "Availability_" + i);
                    }
                }

                // Objetivo: maximizar total de unidades
                IloLinearNumExpr obj = cplex.linearNumExpr();
                for (int o = 0; o < nOrders; o++) {
                    obj.addTerm(totalUnits[o], x[o]);
                }
                cplex.addMaximize(obj);

                // Resolver para diferentes valores de k em paralelo
                List<Future<SolutionResult>> futures = new ArrayList<>();
                for (int k = 1; k <= nAisles; k++) {
                    final int kFinal = k;
                    futures.add(executor.submit(() -> solveForK(cplex, x, y, kFinal, totalUnits, itemToOrders)));
                }

                // Coletar resultados
                for (Future<SolutionResult> future : futures) {
                    SolutionResult result = future.get();
                    if (result != null && result.ratio > bestRatio.get()) {
                        bestRatio.set(result.ratio);
                        bestSolution.set(result.solution);
                    }
                }

                // Fixar variáveis do grupo atual para a próxima iteração
                if (bestSolution.get() != null && cplex.getStatus() == IloCplex.Status.Optimal) {
                    for (int o = startGroup; o < endGroup; o++) {
                        double val = cplex.getValue(x[o]);
                        x[o].setLB(Math.round(val));
                        x[o].setUB(Math.round(val));
                    }
                }

                cplex.end();
            }
        } catch (IloException | InterruptedException | ExecutionException e) {
            e.printStackTrace();
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return bestSolution.get();
    }

    private SolutionResult solveForK(IloCplex cplexBase, IloNumVar[] x, IloIntVar[] y, int k,
                                     int[] totalUnits, Map<Integer, List<int[]>> itemToOrders) throws IloException {
        IloCplex cplex = new IloCplex();
        cplex.setParam(IloCplex.Param.Output.WriteLevel, 0);

        // Copiar variáveis e restrições
        IloNumVar[] xCopy = new IloNumVar[x.length];
        IloIntVar[] yCopy = cplex.boolVarArray(y.length);
        for (int o = 0; o < x.length; o++) {
            xCopy[o] = cplex.numVar(x[o].getLB(), x[o].getUB());
        }

        // Restrições de tamanho da wave
        IloLinearNumExpr waveSizeExpr = cplex.linearNumExpr();
        for (int o = 0; o < x.length; o++) {
            waveSizeExpr.addTerm(totalUnits[o], xCopy[o]);
        }
        cplex.addGe(waveSizeExpr, waveSizeLB);
        cplex.addLe(waveSizeExpr, waveSizeUB);

        // Restrições de disponibilidade
        for (int i = 0; i < nItems; i++) {
            if (!itemToOrders.get(i).isEmpty()) {
                IloLinearNumExpr demand = cplex.linearNumExpr();
                IloLinearNumExpr supply = cplex.linearNumExpr();
                for (int[] order : itemToOrders.get(i)) {
                    demand.addTerm(order[1], xCopy[order[0]]);
                }
                for (int[] aisle : itemToAisles.get(i)) {
                    supply.addTerm(aisle[1], yCopy[aisle[0]]);
                }
                cplex.addLe(demand, supply);
            }
        }

        // Limite de corredores
        cplex.addLe(cplex.sum(yCopy), k);

        // Objetivo
        IloLinearNumExpr obj = cplex.linearNumExpr();
        for (int o = 0; o < x.length; o++) {
            obj.addTerm(totalUnits[o], xCopy[o]);
        }
        cplex.addMaximize(obj);

        // Resolver
        if (cplex.solve() && cplex.getStatus() == IloCplex.Status.Optimal) {
            double v_k = cplex.getObjValue();
            double ratio = v_k / k;
            Set<Integer> selectedOrders = new HashSet<>();
            Set<Integer> selectedAisles = new HashSet<>();
            for (int o = 0; o < x.length; o++) {
                if (cplex.getValue(xCopy[o]) > 0.5) selectedOrders.add(o);
            }
            for (int a = 0; a < y.length; a++) {
                if (cplex.getValue(yCopy[a]) > 0.5) selectedAisles.add(a);
            }
            ChallengeSolution solution = new ChallengeSolution(selectedOrders, selectedAisles);
            if (isSolutionFeasible(solution)) {
                cplex.end();
                return new SolutionResult(solution, ratio);
            }
        }
        cplex.end();
        return null;
    }

    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0);
    }

    protected boolean isSolutionFeasible(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return false;
        }

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) {
            return false;
        }

        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                return false;
            }
        }

        return true;
    }

    protected double computeObjectiveFunction(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }
        int totalUnitsPicked = 0;

        for (int order : selectedOrders) {
            totalUnitsPicked += orders.get(order).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        int numVisitedAisles = visitedAisles.size();
        return (double) totalUnitsPicked / numVisitedAisles;
    }

    private static class SolutionResult {
        ChallengeSolution solution;
        double ratio;

        SolutionResult(ChallengeSolution solution, double ratio) {
            this.solution = solution;
            this.ratio = ratio;
        }
    }
}