package org.sbpo2025.challenge;

import ilog.concert.*;
import ilog.cplex.IloCplex;
import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class ChallengeSolver {

    /* -------------------- parâmetros globais -------------------- */
    private static final int  MAX_RESTARTS      = 8;
    private static final long MAX_WALL_CLOCK_MS = 10L * 60_000L - 5_000L; // 10 min – 5 s

    private final ProblemData data;
    private StopWatch timer;         // recebido do main

    public ChallengeSolver(List<Map<Integer,Integer>> orders,
                           List<Map<Integer,Integer>> aisles,
                           int itemTypes,
                           int minWave,
                           int maxWave) {
        this.data = new ProblemData(orders, aisles, itemTypes, minWave, maxWave);
    }

    /* ========================== solve() ========================= */
    public ChallengeSolution solve(StopWatch externalWatch) {
        this.timer = externalWatch;

        double       bestRatio     = -1;
        Set<Integer> bestOrders    = null;
        Set<Integer> bestCorridors = null;
        Random rng = new Random(2112);

        for (int r = 0; r < MAX_RESTARTS && remainingTimeMs() > 2_000; r++) {
            if (data.corridorCount <= 20) {                    // enumeração
                for (int k = 1; k <= data.corridorCount && remainingTimeMs() > 2_000; k++) {
                    IloResult res = solveFixedCorridorCount(k, remainingTimeMs());
                    if (res.feasible && res.ratio > bestRatio) {
                        bestRatio     = res.ratio;
                        bestOrders    = res.chosenOrders;
                        bestCorridors = res.chosenCorridors;
                    }
                }
            } else {                                           // Dinkelbach
                double lambda = rng.nextDouble() * data.maxWaveItems;
                for (int it = 0; it < 50 && remainingTimeMs() > 2_000; it++) {
                    IloResult res = solveLambda(lambda, remainingTimeMs());
                    if (!res.feasible) break;

                    if (res.ratio > bestRatio) {
                        bestRatio     = res.ratio;
                        bestOrders    = res.chosenOrders;
                        bestCorridors = res.chosenCorridors;
                    }
                    if (res.corridorsUsed == 0) break;

                    double newLambda = (double) res.totalItems / res.corridorsUsed;
                    if (Math.abs(newLambda - lambda) < 1e-3) break;
                    lambda = newLambda;
                }
            }
        }
        return (bestOrders == null)
                ? new ChallengeSolution(Set.of(), Set.of())
                : new ChallengeSolution(bestOrders, bestCorridors);
    }

    /* ===================== modelos de otimização ==================== */

    private IloResult solveFixedCorridorCount(int k, long timeMs) {
        try (IloCplex c = new IloCplex()) {
            c.setOut(null);
            c.setParam(IloCplex.Param.TimeLimit, timeMs / 1000.0);
            setThreads(c);

            IloNumVar[] x = boolArray(c, data.orderCount,  "o");
            IloNumVar[] y = boolArray(c, data.corridorCount,"a");

            IloLinearNumExpr items = c.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++)
                items.addTerm(data.itemsPerOrder[o], x[o]);
            c.addGe(items, data.minWaveItems);
            c.addLe(items, data.maxWaveItems);

            addCapacityConstraints(c, x, y);

            IloLinearNumExpr corrExpr = c.linearNumExpr();
            for (IloNumVar v : y) corrExpr.addTerm(1, v);
            c.addEq(corrExpr, k);

            c.addMaximize(items);
            warmStart(c, x, y, k);

            if (!c.solve() || !(c.getStatus() == IloCplex.Status.Optimal
                             || c.getStatus() == IloCplex.Status.Feasible))
                return IloResult.infeasible();

            return extractResult(c, x, y, items);
        } catch (IloException ex) { return IloResult.infeasible(); }
    }

    private IloResult solveLambda(double lambda, long timeMs) {
        try (IloCplex c = new IloCplex()) {
            c.setOut(null);
            c.setParam(IloCplex.Param.TimeLimit, timeMs / 1000.0);
            c.setParam(IloCplex.Param.Emphasis.MIP, 1);
            setThreads(c);

            IloNumVar[] x = boolArray(c, data.orderCount,  "o");
            IloNumVar[] y = boolArray(c, data.corridorCount,"a");

            IloLinearNumExpr items = c.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++)
                items.addTerm(data.itemsPerOrder[o], x[o]);
            c.addGe(items, data.minWaveItems);
            c.addLe(items, data.maxWaveItems);

            addCapacityConstraints(c, x, y);

            IloLinearNumExpr obj = c.linearNumExpr();
            for (int o = 0; o < data.orderCount;   o++) obj.addTerm(data.itemsPerOrder[o], x[o]);
            for (int a = 0; a < data.corridorCount; a++) obj.addTerm(-lambda, y[a]);
            c.addMaximize(obj);

            warmStart(c, x, y, -1);

            if (!c.solve() || !(c.getStatus() == IloCplex.Status.Optimal
                             || c.getStatus() == IloCplex.Status.Feasible))
                return IloResult.infeasible();

            return extractResult(c, x, y, items);
        } catch (IloException ex) { return IloResult.infeasible(); }
    }

    /* -------------------------- utilitários ------------------------- */

    private static IloNumVar[] boolArray(IloCplex c, int n, String p) throws IloException {
        IloNumVar[] v = new IloNumVar[n];
        for (int i = 0; i < n; i++) v[i] = c.boolVar(p + "_" + i);
        return v;
    }

    private void addCapacityConstraints(IloCplex c, IloNumVar[] x, IloNumVar[] y) throws IloException {
        for (int i = 0; i < data.itemCount; i++) {
            if (data.ordersRequiringItem[i].isEmpty()) continue;

            IloLinearNumExpr demand = c.linearNumExpr();
            for (int o : data.ordersRequiringItem[i])
                demand.addTerm(data.orderDemand.get(o).get(i), x[o]);

            IloLinearNumExpr supply = c.linearNumExpr();
            for (int a : data.corridorsContainingItem[i])
                supply.addTerm(data.corridorSupply.get(a).get(i), y[a]);

            c.addLe(demand, supply);
        }
    }

    private void setThreads(IloCplex c) throws IloException {
        String t = System.getenv("CPLEX_THREADS");
        if (t != null && !t.isBlank()) {
            try { c.setParam(IloCplex.Param.Threads, Integer.parseInt(t.trim())); }
            catch (NumberFormatException ignore) {}
        }
    }

    /* warm‑start guloso */
    private void warmStart(IloCplex c, IloNumVar[] x, IloNumVar[] y, int corrLimit) throws IloException {
        boolean[] oSel = new boolean[data.orderCount];
        boolean[] aSel = new boolean[data.corridorCount];
        int items = 0;

        Integer[] ord = IntStream.range(0, data.orderCount).boxed()
                .sorted((a,b)->Integer.compare(data.itemsPerOrder[b], data.itemsPerOrder[a]))
                .toArray(Integer[]::new);

        for (int o : ord) {
            int newItems = items + data.itemsPerOrder[o];
            if (newItems > data.maxWaveItems) continue;

            List<Integer> extra = new ArrayList<>();
            boolean ok = true;
            for (var e : data.orderDemand.get(o).entrySet()) {
                int it = e.getKey(), q = e.getValue();
                int sup = 0;
                for (int a = 0; a < aSel.length && sup < q; a++)
                    if (aSel[a]) sup += data.corridorSupply.get(a).getOrDefault(it, 0);
                if (sup >= q) continue;

                for (int a : data.corridorsContainingItem[it]) if (!aSel[a]) {
                    extra.add(a);
                    sup += data.corridorSupply.get(a).get(it);
                    if (sup >= q) break;
                }
                if (sup < q) { ok = false; break; }
            }
            if (!ok) continue;

            long corrNow = IntStream.range(0, aSel.length).filter(i -> aSel[i]).count();
            if (corrLimit >= 0 && corrNow + extra.size() > corrLimit) continue;

            oSel[o] = true;
            items = newItems;
            extra.forEach(a -> aSel[a] = true);

            if (items >= data.minWaveItems &&
                    (corrLimit < 0 || corrNow + extra.size() == corrLimit))
                break;
        }
        if (items < data.minWaveItems) return;

        int n = x.length + y.length;
        IloNumVar[] vars = new IloNumVar[n];
        double[]     val = new double[n];
        int p = 0;
        for (int i = 0; i < x.length; i++) { vars[p] = x[i]; val[p++] = oSel[i] ? 1 : 0; }
        for (int i = 0; i < y.length; i++) { vars[p] = y[i]; val[p++] = aSel[i] ? 1 : 0; }

        c.addMIPStart(vars, val, IloCplex.MIPStartEffort.Auto, "warm");
    }

    private IloResult extractResult(IloCplex c, IloNumVar[] x, IloNumVar[] y,
                                    IloLinearNumExpr itemsExpr) throws IloException {
        Set<Integer> ord = new HashSet<>();
        Set<Integer> ais = new HashSet<>();
        for (int o = 0; o < x.length; o++) if (c.getValue(x[o]) > 0.5) ord.add(o);
        for (int a = 0; a < y.length; a++) if (c.getValue(y[a]) > 0.5) ais.add(a);

        int items = (int) Math.round(c.getValue(itemsExpr));
        int corrs = ais.size();
        double ratio = corrs == 0 ? 0.0 : (double) items / corrs;
        return new IloResult(true, ratio, items, corrs, ord, ais);
    }

    private long remainingTimeMs() {
        return Math.max(MAX_WALL_CLOCK_MS - timer.getTime(TimeUnit.MILLISECONDS), 0);
    }

    /* ============================ dados ============================= */
    private static class ProblemData {
        final int orderCount, itemCount, corridorCount;
        final int minWaveItems, maxWaveItems;

        final Map<Integer,Map<Integer,Integer>> orderDemand    = new HashMap<>();
        final Map<Integer,Map<Integer,Integer>> corridorSupply = new HashMap<>();

        final int[] itemsPerOrder;
        final List<Integer>[] ordersRequiringItem;
        final List<Integer>[] corridorsContainingItem;

        @SuppressWarnings("unchecked")
        ProblemData(List<Map<Integer,Integer>> orders,
                    List<Map<Integer,Integer>> aisles,
                    int items,
                    int lb, int ub) {

            orderCount    = orders.size();
            corridorCount = aisles.size();
            itemCount     = items;
            minWaveItems  = lb;
            maxWaveItems  = ub;

            itemsPerOrder           = new int[orderCount];
            ordersRequiringItem     = new List[itemCount];
            corridorsContainingItem = new List[itemCount];
            for (int i = 0; i < itemCount; i++) {
                ordersRequiringItem[i]     = new ArrayList<>();
                corridorsContainingItem[i] = new ArrayList<>();
            }

            /* pedidos */
            for (int o = 0; o < orderCount; o++) {
                var map = orders.get(o);
                orderDemand.put(o, map);
                int sum = 0;
                for (var e : map.entrySet()) {
                    int it = e.getKey(), q = e.getValue();
                    sum += q;
                    ordersRequiringItem[it].add(o);
                }
                itemsPerOrder[o] = sum;
            }

            /* corredores */
            for (int a = 0; a < corridorCount; a++) {
                var map = aisles.get(a);
                corridorSupply.put(a, map);
                for (int it : map.keySet())
                    corridorsContainingItem[it].add(a);
            }

            pruneDominatedCorridors();
        }

        /** remove corredores cujo suprimento é subconjunto de outro */
        private void pruneDominatedCorridors() {
            boolean[] dominated = new boolean[corridorCount];

            for (int i = 0; i < corridorCount; i++) {
                if (dominated[i]) continue;
                for (int j = 0; j < corridorCount; j++) {
                    if (i == j || dominated[j]) continue;

                    boolean iSubJ = true;
                    for (var e : corridorSupply.get(i).entrySet()) {
                        int it = e.getKey(), q = e.getValue();
                        if (corridorSupply.get(j).getOrDefault(it, 0) < q) {
                            iSubJ = false;
                            break;
                        }
                    }
                    if (iSubJ) {
                        dominated[i] = true;
                        break;
                    }
                }
            }

            for (int a = 0; a < corridorCount; a++) if (dominated[a]) {
                for (int it : corridorSupply.get(a).keySet())
                    corridorsContainingItem[it].remove((Integer) a);
            }
        }
    }

    /* --------------- contêiner de resultado interno ----------------- */
    private record IloResult(boolean feasible,
                             double ratio,
                             int totalItems,
                             int corridorsUsed,
                             Set<Integer> chosenOrders,
                             Set<Integer> chosenCorridors) {
        static IloResult infeasible() {
            return new IloResult(false, -1, 0, 0, Set.of(), Set.of());
        }
    }
}
