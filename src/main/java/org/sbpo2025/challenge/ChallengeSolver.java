package org.sbpo2025.challenge;

import ilog.concert.*;
import ilog.cplex.IloCplex;
import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class ChallengeSolver {

    private static final int MAX_RESTARTS = 8;
    private static final long MAX_WALL_CLOCK_MS = 10 * 60 * 1000 - 5_000; // 10 min minus buffer
    private static final double EPS = 1e-6;

    private final ProblemData data;
    private StopWatch timer;

    public ChallengeSolver(List<Map<Integer, Integer>> orderList,
                           List<Map<Integer, Integer>> corridorList,
                           int itemTypes,
                           int minWaveItems,
                           int maxWaveItems) {
        this.data = new ProblemData(orderList, corridorList, itemTypes, minWaveItems, maxWaveItems);
    }

    public ChallengeSolution solve(StopWatch externalWatch) {
        this.timer = externalWatch;

        double bestRatio = -1;
        Set<Integer> bestOrders = null;
        Set<Integer> bestCorridors = null;

        Random random = new Random(2112);

        for (int restart = 0; restart < MAX_RESTARTS && remainingTimeMs() > 2_000; restart++) {

            if (data.corridorCount <= 20) {
                for (int corridorsTarget = 1;
                     corridorsTarget <= data.corridorCount && remainingTimeMs() > 2_000;
                     corridorsTarget++) {

                    IloResult res = solveFixedCorridorCount(corridorsTarget, remainingTimeMs());
                    if (res.feasible && res.ratio > bestRatio) {
                        bestRatio = res.ratio;
                        bestOrders = res.chosenOrders;
                        bestCorridors = res.chosenCorridors;
                    }
                }
            } else {
                double lambda = random.nextDouble() * data.maxWaveItems;
                for (int iteration = 0; iteration < 50 && remainingTimeMs() > 2_000; iteration++) {
                    IloResult res = solveLambda(lambda, remainingTimeMs());
                    if (!res.feasible) break;

                    if (res.ratio > bestRatio) {
                        bestRatio = res.ratio;
                        bestOrders = res.chosenOrders;
                        bestCorridors = res.chosenCorridors;
                    }
                    if (res.corridorsUsed == 0) break;

                    double newLambda = (double) res.totalItems / res.corridorsUsed;
                    if (Math.abs(newLambda - lambda) < 1e-3) break;
                    lambda = newLambda;
                }
            }
        }

        if (bestOrders == null) {
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }
        return new ChallengeSolution(bestOrders, bestCorridors);
    }

    /* -----------------------  Private helpers ---------------------- */

    private IloResult solveFixedCorridorCount(int corridorsTarget, long timeMs) {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.TimeLimit, timeMs / 1000.0);

            IloNumVar[] orderVar = new IloNumVar[data.orderCount];
            for (int o = 0; o < data.orderCount; o++) {
                orderVar[o] = cplex.boolVar("ord_" + o);
            }
            IloNumVar[] corridorVar = new IloNumVar[data.corridorCount];
            for (int a = 0; a < data.corridorCount; a++) {
                corridorVar[a] = cplex.boolVar("cor_" + a);
            }

            IloLinearNumExpr itemsExpr = cplex.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++) {
                itemsExpr.addTerm(data.itemsPerOrder[o], orderVar[o]);
            }
            cplex.addGe(itemsExpr, data.minWaveItems);
            cplex.addLe(itemsExpr, data.maxWaveItems);

            for (int item = 0; item < data.itemCount; item++) {
                if (data.ordersRequiringItem[item].isEmpty()) continue;
                IloLinearNumExpr required = cplex.linearNumExpr();
                for (int o : data.ordersRequiringItem[item]) {
                    required.addTerm(data.orderDemand.get(o).get(item), orderVar[o]);
                }
                IloLinearNumExpr supply = cplex.linearNumExpr();
                for (int a : data.corridorsContainingItem[item]) {
                    supply.addTerm(data.corridorSupply.get(a).get(item), corridorVar[a]);
                }
                cplex.addLe(required, supply);
            }

            IloLinearNumExpr corridorExpr = cplex.linearNumExpr();
            for (IloNumVar v : corridorVar) corridorExpr.addTerm(1, v);
            cplex.addEq(corridorExpr, corridorsTarget);

            cplex.addMaximize(itemsExpr);

            if (!cplex.solve() ||
                    !(cplex.getStatus() == IloCplex.Status.Optimal || cplex.getStatus() == IloCplex.Status.Feasible)) {
                return IloResult.infeasible();
            }

            Set<Integer> ordersChosen = new HashSet<>();
            for (int o = 0; o < data.orderCount; o++) if (cplex.getValue(orderVar[o]) > .5) ordersChosen.add(o);
            Set<Integer> corridorsChosen = new HashSet<>();
            for (int a = 0; a < data.corridorCount; a++) if (cplex.getValue(corridorVar[a]) > .5) corridorsChosen.add(a);

            int itemsPicked = (int) Math.round(cplex.getValue(itemsExpr));
            double ratio = (double) itemsPicked / corridorsTarget;
            return new IloResult(true, ratio, itemsPicked, corridorsTarget, ordersChosen, corridorsChosen);

        } catch (IloException e) {
            return IloResult.infeasible();
        }
    }

    private IloResult solveLambda(double lambda, long timeMs) {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.TimeLimit, timeMs / 1000.0);

            IloNumVar[] orderVar = new IloNumVar[data.orderCount];
            for (int o = 0; o < data.orderCount; o++) orderVar[o] = cplex.boolVar("ord_" + o);
            IloNumVar[] corridorVar = new IloNumVar[data.corridorCount];
            for (int a = 0; a < data.corridorCount; a++) corridorVar[a] = cplex.boolVar("cor_" + a);

            IloLinearNumExpr itemsExpr = cplex.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++) itemsExpr.addTerm(data.itemsPerOrder[o], orderVar[o]);
            cplex.addGe(itemsExpr, data.minWaveItems);
            cplex.addLe(itemsExpr, data.maxWaveItems);

            for (int item = 0; item < data.itemCount; item++) {
                if (data.ordersRequiringItem[item].isEmpty()) continue;
                IloLinearNumExpr required = cplex.linearNumExpr();
                for (int o : data.ordersRequiringItem[item]) {
                    required.addTerm(data.orderDemand.get(o).get(item), orderVar[o]);
                }
                IloLinearNumExpr supply = cplex.linearNumExpr();
                for (int a : data.corridorsContainingItem[item]) {
                    supply.addTerm(data.corridorSupply.get(a).get(item), corridorVar[a]);
                }
                cplex.addLe(required, supply);
            }

            IloLinearNumExpr obj = cplex.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++) obj.addTerm(data.itemsPerOrder[o], orderVar[o]);
            for (int a = 0; a < data.corridorCount; a++) obj.addTerm(-lambda, corridorVar[a]);
            cplex.addMaximize(obj);

            if (!cplex.solve() ||
                    !(cplex.getStatus() == IloCplex.Status.Optimal || cplex.getStatus() == IloCplex.Status.Feasible)) {
                return IloResult.infeasible();
            }

            Set<Integer> ordersChosen = new HashSet<>();
            for (int o = 0; o < data.orderCount; o++) if (cplex.getValue(orderVar[o]) > .5) ordersChosen.add(o);
            Set<Integer> corridorsChosen = new HashSet<>();
            for (int a = 0; a < data.corridorCount; a++) if (cplex.getValue(corridorVar[a]) > .5) corridorsChosen.add(a);

            int itemsPicked = (int) Math.round(cplex.getValue(itemsExpr));
            int corridorsUsed = corridorsChosen.size();
            double ratio = corridorsUsed == 0 ? 0 : (double) itemsPicked / corridorsUsed;

            return new IloResult(true, ratio, itemsPicked, corridorsUsed, ordersChosen, corridorsChosen);

        } catch (IloException e) {
            return IloResult.infeasible();
        }
    }

    private long remainingTimeMs() {
        return Math.max(MAX_WALL_CLOCK_MS - timer.getTime(TimeUnit.MILLISECONDS), 0);
    }

    /* -------------------- inner helper classes -------------------- */

    private static class ProblemData {
        final int orderCount;
        final int itemCount;
        final int corridorCount;
        final int minWaveItems;
        final int maxWaveItems;

        final Map<Integer, Map<Integer, Integer>> orderDemand = new HashMap<>();
        final Map<Integer, Map<Integer, Integer>> corridorSupply = new HashMap<>();

        final int[] itemsPerOrder;
        final List<Integer>[] ordersRequiringItem;
        final List<Integer>[] corridorsContainingItem;

        @SuppressWarnings("unchecked")
        ProblemData(List<Map<Integer, Integer>> orders,
                    List<Map<Integer, Integer>> corridors,
                    int itemTypes,
                    int minWave,
                    int maxWave) {

            this.orderCount = orders.size();
            this.corridorCount = corridors.size();
            this.itemCount = itemTypes;
            this.minWaveItems = minWave;
            this.maxWaveItems = maxWave;

            this.itemsPerOrder = new int[orderCount];

            this.ordersRequiringItem = new List[itemCount];
            this.corridorsContainingItem = new List[itemCount];
            for (int i = 0; i < itemCount; i++) {
                ordersRequiringItem[i] = new ArrayList<>();
                corridorsContainingItem[i] = new ArrayList<>();
            }

            for (int o = 0; o < orderCount; o++) {
                Map<Integer, Integer> map = orders.get(o);
                orderDemand.put(o, map);
                int total = 0;
                for (Map.Entry<Integer, Integer> e : map.entrySet()) {
                    int item = e.getKey();
                    int q = e.getValue();
                    total += q;
                    ordersRequiringItem[item].add(o);
                }
                itemsPerOrder[o] = total;
            }

            for (int a = 0; a < corridorCount; a++) {
                Map<Integer, Integer> map = corridors.get(a);
                corridorSupply.put(a, map);
                for (int item : map.keySet()) {
                    corridorsContainingItem[item].add(a);
                }
            }
        }
    }

    private static class IloResult {
        final boolean feasible;
        final double ratio;
        final int totalItems;
        final int corridorsUsed;
        final Set<Integer> chosenOrders;
        final Set<Integer> chosenCorridors;

        private IloResult(boolean feasible,
                          double ratio,
                          int totalItems,
                          int corridorsUsed,
                          Set<Integer> chosenOrders,
                          Set<Integer> chosenCorridors) {
            this.feasible = feasible;
            this.ratio = ratio;
            this.totalItems = totalItems;
            this.corridorsUsed = corridorsUsed;
            this.chosenOrders = chosenOrders;
            this.chosenCorridors = chosenCorridors;
        }

        static IloResult infeasible() {
            return new IloResult(false, -1, 0, 0,
                    Collections.emptySet(), Collections.emptySet());
        }
    }
}
