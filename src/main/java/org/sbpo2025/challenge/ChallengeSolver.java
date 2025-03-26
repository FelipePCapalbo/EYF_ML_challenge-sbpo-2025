package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Exemplo de ChallengeSolver que faz:
 *  - Construção Gulosa inicial para obter wave factível.
 *  - Melhoria (GRASP + VND) respeitando LB/UB e cobertura.
 */
public class ChallengeSolver {

    // Tempo máximo (ms) por instância (por segurança, definimos menor que 10min se quiser).
    private final long MAX_RUNTIME_MS = 600_000;

    // Dados da instância
    protected final List<Map<Integer, Integer>> orders; // Lista de "pedidos" (cada um é Map<itemID, qtd>)
    protected final List<Map<Integer, Integer>> aisles; // Lista de "corredores" (cada um é Map<itemID, qtdDisp>)
    protected final int nItems;                        // Número total de itens possíveis
    protected final int waveSizeLB;                    // LB (limite inferior de total de itens)
    protected final int waveSizeUB;                    // UB (limite superior de total de itens)

    public ChallengeSolver(List<Map<Integer, Integer>> orders,
                           List<Map<Integer, Integer>> aisles,
                           int nItems,
                           int waveSizeLB,
                           int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    /**
     * Método principal que o Challenge.java chama para resolver a instância.
     */
    public ChallengeSolution solve(StopWatch stopWatch) {
        long start = stopWatch.getTime();
        long deadline = start + MAX_RUNTIME_MS;

        // 1) Constrói uma solução gulosa inicial (factível ou vazia, se falhar)
        ChallengeSolution bestSol = buildInitialFeasibleSolution(stopWatch, deadline);

        // 2) Se não veio nada factível, faz fallback trivial
        if (!isSolutionFeasible(bestSol)) {
            bestSol = buildTrivialFallback();
        }

        // 3) Aplica GRASP + VND, respeitando o limite de tempo
        bestSol = improveWithGraspVnd(bestSol, stopWatch, deadline);

        // 4) Retorna a melhor
        if (!isSolutionFeasible(bestSol)) {
            // Se por algum motivo ficou inviável, fallback
            bestSol = buildTrivialFallback();
        }
        return bestSol;
    }

    /**
     * Fase 1: Constrói uma wave factível de forma simples e gulosa.
     * Tenta chegar em LB<= somaItens <= UB; se não der, retorna wave vazia.
     */
    private ChallengeSolution buildInitialFeasibleSolution(StopWatch sw, long deadline) {
        // Ordenamos pedidos por quantidade de itens (decrescente).
        List<Integer> allOrders = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            allOrders.add(i);
        }
        // Mapeia para (pedido -> somaItens).
        Map<Integer, Integer> orderWeights = new HashMap<>();
        for (int i : allOrders) {
            orderWeights.put(i, sumOrder(orders.get(i)));
        }
        // Ordena decrescente
        allOrders.sort((o1, o2) -> Integer.compare(orderWeights.get(o2), orderWeights.get(o1)));

        Set<Integer> chosen = new HashSet<>();
        int currentSum = 0;

        // Itera sobre os pedidos na ordem decrescente e pega se couber (tentar não estourar UB).
        for (int ord : allOrders) {
            long now = sw.getTime();
            if (now >= deadline) break; // acabou tempo
            int w = orderWeights.get(ord);

            if (currentSum + w <= waveSizeUB) {
                // Adiciona
                chosen.add(ord);
                currentSum += w;
                // Se já passamos de LB, poderíamos parar, mas tentamos continuar
                if (currentSum >= waveSizeLB) {
                    // Podemos continuar a inserir, mas é arbitrário. 
                    // Se quiser parar ao bater LB, descomente:
                    // break;
                }
            }
        }

        // Checa se respeitamos LB
        if (currentSum < waveSizeLB) {
            // Tentar ainda inserir pedidos menores que caibam
            for (int ord : allOrders) {
                if (chosen.contains(ord)) continue;
                long now = sw.getTime();
                if (now >= deadline) break;

                int w = orderWeights.get(ord);
                if (currentSum + w <= waveSizeUB) {
                    chosen.add(ord);
                    currentSum += w;
                    if (currentSum >= waveSizeLB) {
                        break;
                    }
                }
            }
        }

        // Se ao final não estiver em [LB, UB], devolve wave vazia
        if (currentSum < waveSizeLB || currentSum > waveSizeUB) {
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }

        // Constrói conjunto de corredores que cubra
        Set<Integer> chosenAisles = coverOrders(chosen);
        ChallengeSolution sol = new ChallengeSolution(chosen, chosenAisles);

        // Se a cobertura não for viável, retorna wave vazia
        if (!isSolutionFeasible(sol)) {
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }
        return sol;
    }

    /**
     * Constrói uma solução fallback trivial: 
     * Ex.: tenta um único pedido que caiba em LB<=sum<=UB e acha corredores que cubram.
     * Se falhar, retorna wave vazia.
     */
    private ChallengeSolution buildTrivialFallback() {
        for (int o = 0; o < orders.size(); o++) {
            int sum = sumOrder(orders.get(o));
            if (sum >= waveSizeLB && sum <= waveSizeUB) {
                // tenta cobrir
                Set<Integer> one = new HashSet<>(Collections.singletonList(o));
                Set<Integer> ais = coverOrders(one);
                ChallengeSolution sol = new ChallengeSolution(one, ais);
                if (isSolutionFeasible(sol)) {
                    return sol;
                }
            }
        }
        // nada achou
        return new ChallengeSolution(new HashSet<>(), new HashSet<>());
    }

    /**
     * Fase 2: GRASP + VND para melhorar a solução. (Single-thread, simplificado.)
     */
    private ChallengeSolution improveWithGraspVnd(ChallengeSolution startSol,
                                                  StopWatch sw,
                                                  long deadline) {
        ChallengeSolution best = startSol;
        double bestRatio = computeObjectiveFunction(best);

        // Vamos fazer iterações de GRASP até o tempo acabar
        while (true) {
            long now = sw.getTime();
            if (now >= deadline) break; // tempo esgotou

            // Constrói rapidamente uma variação aleatória da wave
            ChallengeSolution cand = buildGraspSolution(sw, deadline);

            // Se factível, faz VND
            if (isSolutionFeasible(cand)) {
                cand = vndImprove(cand, sw, deadline);
                // Checar ratio
                double r = computeObjectiveFunction(cand);
                if (r > bestRatio) {
                    bestRatio = r;
                    best = cand;
                }
            }
        }
        return best;
    }

    /**
     * Construtor GRASP (simples e randômico).
     * - Tenta construir wave repetindo "selecionar pedido" aleatório se couber em LB/UB.
     */
    private ChallengeSolution buildGraspSolution(StopWatch sw, long deadline) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            all.add(i);
        }
        // shuffle
        Collections.shuffle(all);

        Set<Integer> chosen = new HashSet<>();
        int sum = 0;
        for (int ord : all) {
            long now = sw.getTime();
            if (now >= deadline) break;
            int w = sumOrder(orders.get(ord));
            if (sum + w <= waveSizeUB) {
                chosen.add(ord);
                sum += w;
                if (sum >= waveSizeLB) {
                    // opcionalmente paramos
                    // break;
                }
            }
        }
        // se sum < LB => inviável, mas tentamos
        if (sum < waveSizeLB) {
            // devolve wave vazia por simplicidade
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }
        // gera corredores
        Set<Integer> ais = coverOrders(chosen);
        return new ChallengeSolution(chosen, ais);
    }

    /**
     * Aplica VND, com 3 vizinhanças (InsertRemove, Swap1x1, Swap2x1).
     */
    private ChallengeSolution vndImprove(ChallengeSolution sol, StopWatch sw, long deadline) {
        ChallengeSolution best = sol;
        double bestRatio = computeObjectiveFunction(best);

        int kMax = 3;
        int k = 1;
        while (k <= kMax) {
            long now = sw.getTime();
            if (now >= deadline) break;

            ChallengeSolution newSol;
            switch (k) {
                case 1:
                    newSol = neighborhoodInsertRemove(best, sw, deadline);
                    break;
                case 2:
                    newSol = neighborhoodSwap1x1(best, sw, deadline);
                    break;
                case 3:
                    newSol = neighborhoodSwap2x1(best, sw, deadline);
                    break;
                default:
                    newSol = best;
                    break;
            }
            double nr = computeObjectiveFunction(newSol);
            if (nr > bestRatio && isSolutionFeasible(newSol)) {
                best = newSol;
                bestRatio = nr;
                k = 1; // recomeça
            } else {
                k++;
            }
        }
        return best;
    }

    // Vizinhanca 1: Insert ou Remove (um pedido)
    private ChallengeSolution neighborhoodInsertRemove(ChallengeSolution current,
                                                       StopWatch sw, long deadline) {
        ChallengeSolution bestLocal = current;
        double bestRatio = computeObjectiveFunction(current);

        Set<Integer> baseOrders = new HashSet<>(current.orders());
        int baseSum = sumOrders(baseOrders);

        // 1) Tentar remover
        for (int o : baseOrders) {
            long now = sw.getTime();
            if (now >= deadline) break;
            int w = sumOrder(orders.get(o));
            int newSum = baseSum - w;
            if (newSum < waveSizeLB || newSum > waveSizeUB) {
                continue; // inviável
            }
            // remove e recalcula
            Set<Integer> newSet = new HashSet<>(baseOrders);
            newSet.remove(o);
            Set<Integer> ais = coverOrders(newSet);
            ChallengeSolution cand = new ChallengeSolution(newSet, ais);
            if (isSolutionFeasible(cand)) {
                double r = computeObjectiveFunction(cand);
                if (r > bestRatio) {
                    bestLocal = cand;
                    bestRatio = r;
                }
            }
        }

        // 2) Tentar adicionar
        List<Integer> outside = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (!baseOrders.contains(i)) {
                outside.add(i);
            }
        }
        for (int o : outside) {
            long now = sw.getTime();
            if (now >= deadline) break;
            int w = sumOrder(orders.get(o));
            int newSum = baseSum + w;
            if (newSum < waveSizeLB || newSum > waveSizeUB) {
                continue;
            }
            Set<Integer> newSet = new HashSet<>(baseOrders);
            newSet.add(o);
            Set<Integer> ais = coverOrders(newSet);
            ChallengeSolution cand = new ChallengeSolution(newSet, ais);
            if (isSolutionFeasible(cand)) {
                double r = computeObjectiveFunction(cand);
                if (r > bestRatio) {
                    bestLocal = cand;
                    bestRatio = r;
                }
            }
        }
        return bestLocal;
    }

    // Vizinhanca 2: Swap 1x1 (substitui um pedido do conjunto por outro de fora)
    private ChallengeSolution neighborhoodSwap1x1(ChallengeSolution current,
                                                  StopWatch sw, long deadline) {
        ChallengeSolution bestLocal = current;
        double bestRatio = computeObjectiveFunction(current);

        Set<Integer> baseOrders = new HashSet<>(current.orders());
        int baseSum = sumOrders(baseOrders);
        List<Integer> inside = new ArrayList<>(baseOrders);
        List<Integer> outside = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (!baseOrders.contains(i)) {
                outside.add(i);
            }
        }
        for (int in : inside) {
            int win = sumOrder(orders.get(in));
            for (int out : outside) {
                long now = sw.getTime();
                if (now >= deadline) break;

                int wout = sumOrder(orders.get(out));
                int newSum = baseSum - win + wout;
                if (newSum < waveSizeLB || newSum > waveSizeUB) {
                    continue;
                }
                Set<Integer> newSet = new HashSet<>(baseOrders);
                newSet.remove(in);
                newSet.add(out);
                Set<Integer> ais = coverOrders(newSet);
                ChallengeSolution cand = new ChallengeSolution(newSet, ais);
                if (isSolutionFeasible(cand)) {
                    double r = computeObjectiveFunction(cand);
                    if (r > bestRatio) {
                        bestLocal = cand;
                        bestRatio = r;
                    }
                }
            }
        }
        return bestLocal;
    }

    // Vizinhanca 3: Swap 2x1 (remove 2 pedidos e adiciona 1), para tentar liberar espaço e ficar no LB/UB.
    private ChallengeSolution neighborhoodSwap2x1(ChallengeSolution current,
                                                  StopWatch sw, long deadline) {
        ChallengeSolution bestLocal = current;
        double bestRatio = computeObjectiveFunction(current);

        Set<Integer> baseOrders = new HashSet<>(current.orders());
        int baseSum = sumOrders(baseOrders);
        List<Integer> inside = new ArrayList<>(baseOrders);
        List<Integer> outside = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (!baseOrders.contains(i)) {
                outside.add(i);
            }
        }

        // Remove 2, add 1
        for (int i = 0; i < inside.size(); i++) {
            for (int j = i + 1; j < inside.size(); j++) {
                long now = sw.getTime();
                if (now >= deadline) break;

                int o1 = inside.get(i);
                int o2 = inside.get(j);
                int w1 = sumOrder(orders.get(o1));
                int w2 = sumOrder(orders.get(o2));
                int partialSum = baseSum - w1 - w2;
                if (partialSum < 0) continue; // erro

                for (int out : outside) {
                    long now2 = sw.getTime();
                    if (now2 >= deadline) break;

                    int wout = sumOrder(orders.get(out));
                    int newSum = partialSum + wout;
                    if (newSum >= waveSizeLB && newSum <= waveSizeUB) {
                        Set<Integer> newSet = new HashSet<>(baseOrders);
                        newSet.remove(o1);
                        newSet.remove(o2);
                        newSet.add(out);

                        Set<Integer> ais = coverOrders(newSet);
                        ChallengeSolution cand = new ChallengeSolution(newSet, ais);
                        if (isSolutionFeasible(cand)) {
                            double r = computeObjectiveFunction(cand);
                            if (r > bestRatio) {
                                bestLocal = cand;
                                bestRatio = r;
                            }
                        }
                    }
                }
            }
        }
        return bestLocal;
    }

    /**
     * Verifica se a solution é factível:
     * - LB <= totalItems <= UB
     * - Todos os itens demandados pelos pedidos escolhidos são cobertos pelos corredores.
     */
    protected boolean isSolutionFeasible(ChallengeSolution sol) {
        if (sol == null) return false;
        Set<Integer> so = sol.orders();
        Set<Integer> sa = sol.aisles();
        if (so == null || sa == null) return false;

        int total = sumOrders(so);
        // Checa LB/UB
        if (total < waveSizeLB || total > waveSizeUB) {
            return false;
        }
        // Checa cobertura
        // - soma demanda de cada item
        int[] demand = new int[nItems];
        for (int o : so) {
            for (Map.Entry<Integer, Integer> e : orders.get(o).entrySet()) {
                demand[e.getKey()] += e.getValue();
            }
        }
        // - soma oferta
        int[] supply = new int[nItems];
        for (int a : sa) {
            for (Map.Entry<Integer, Integer> e : aisles.get(a).entrySet()) {
                supply[e.getKey()] += e.getValue();
            }
        }
        // - compare
        for (int i = 0; i < nItems; i++) {
            if (demand[i] > supply[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Função objetivo: total de itens nos pedidos / #corredores.
     */
    protected double computeObjectiveFunction(ChallengeSolution sol) {
        if (sol == null) return 0.0;
        Set<Integer> so = sol.orders();
        Set<Integer> sa = sol.aisles();
        if (so == null || so.isEmpty() || sa == null || sa.isEmpty()) return 0.0;

        int sum = sumOrders(so);
        return (double) sum / sa.size();
    }

    /**
     * Gera corredores (subconjunto) que cubra integralmente os itens demandados por 'chosenOrders'.
     * Heurística gulosa de "set cover".
     */
    private Set<Integer> coverOrders(Set<Integer> chosenOrders) {
        int[] demand = new int[nItems];
        int totalDemand = 0;
        for (int o : chosenOrders) {
            for (Map.Entry<Integer, Integer> e : orders.get(o).entrySet()) {
                demand[e.getKey()] += e.getValue();
            }
        }
        for (int d : demand) {
            totalDemand += d;
        }
        if (totalDemand == 0) {
            // sem demanda => sem corredores
            return new HashSet<>();
        }

        Set<Integer> usedAisles = new HashSet<>();
        int[] remain = Arrays.copyOf(demand, demand.length);

        // Repete até cobrir toda remain
        while (true) {
            // Verifica se acabou
            boolean allCovered = true;
            for (int i = 0; i < nItems; i++) {
                if (remain[i] > 0) {
                    allCovered = false;
                    break;
                }
            }
            if (allCovered) {
                break;
            }
            // Escolhe corredor que melhor cobre a demanda remanescente
            int bestAisle = -1;
            int bestCover = 0;
            for (int a = 0; a < aisles.size(); a++) {
                if (usedAisles.contains(a)) continue;
                // conta quanto ele cobre
                int potential = 0;
                for (Map.Entry<Integer, Integer> e : aisles.get(a).entrySet()) {
                    int it = e.getKey();
                    int qtd = e.getValue();
                    if (remain[it] > 0) {
                        potential += Math.min(remain[it], qtd);
                    }
                }
                if (potential > bestCover) {
                    bestCover = potential;
                    bestAisle = a;
                }
            }
            if (bestAisle < 0 || bestCover == 0) {
                // nenhum corredor ajuda mais => não dá p/ cobrir
                // retorna todos os corredores se preferir 
                // (ou retorna usedAisles, mas normalmente inviabiliza a wave)
                // aqui retornamos todos, só p/ "tentar" ser generoso:
                Set<Integer> all = new HashSet<>();
                for (int i = 0; i < aisles.size(); i++) {
                    all.add(i);
                }
                return all;
            } else {
                usedAisles.add(bestAisle);
                // atualiza remain
                for (Map.Entry<Integer, Integer> e : aisles.get(bestAisle).entrySet()) {
                    int it = e.getKey();
                    int qtd = e.getValue();
                    if (remain[it] > 0) {
                        remain[it] -= Math.min(remain[it], qtd);
                    }
                }
            }
        }

        return usedAisles;
    }

    /**
     * Soma total de itens de um conjunto de pedidos.
     */
    private int sumOrders(Set<Integer> set) {
        int total = 0;
        for (int o : set) {
            total += sumOrder(orders.get(o));
        }
        return total;
    }

    /**
     * Soma total de itens em um único pedido (map item->quantidade).
     */
    private int sumOrder(Map<Integer, Integer> orderMap) {
        int sum = 0;
        for (int v : orderMap.values()) {
            sum += v;
        }
        return sum;
    }

}
