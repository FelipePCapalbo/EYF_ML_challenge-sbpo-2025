package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementação de uma heurística GRASP + VND para o desafio SBPO,
 * explorando múltiplos núcleos para rodar iterações em paralelo.
 */
public class ChallengeSolver {

    // Tempo máximo (em milissegundos) para o algoritmo (default 10 minutos = 600_000ms).
    // Você pode alterar ou ler esse valor de outro lugar, se preferir.
    private final long MAX_RUNTIME = 60_000;

    // Quantas vezes tentaremos gerar soluções em paralelo a cada vez (ajuste se desejar).
    // Cada thread fará iterações de GRASP até o tempo expirar, compartilhando o "melhor global".
    private final int NUM_THREADS = Runtime.getRuntime().availableProcessors();

    // Dados principais
    protected final List<Map<Integer, Integer>> orders; // Lista de pedidos
    protected final List<Map<Integer, Integer>> aisles; // Lista de corredores
    protected final int nItems;                        // Número total de itens
    protected final int waveSizeLB;                    // Limite inferior de itens (LB)
    protected final int waveSizeUB;                    // Limite superior de itens (UB)

    // Construtor
    public ChallengeSolver(
            List<Map<Integer, Integer>> orders,
            List<Map<Integer, Integer>> aisles,
            int nItems,
            int waveSizeLB,
            int waveSizeUB
    ) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    /**
     * Ponto de entrada para resolver a instância (chamado em Challenge.java).
     */
    public ChallengeSolution solve(org.apache.commons.lang3.time.StopWatch stopWatch) {
        long startMillis = stopWatch.getTime(); // tempo inicial decorrido
        long deadline = startMillis + MAX_RUNTIME; // tempo-limite absoluto (em ms do "stopWatch")

        // Referência atômica para compartilharmos a melhor solução entre threads
        AtomicReference<ChallengeSolution> bestSolRef = new AtomicReference<>(null);
        AtomicReference<Double> bestRatioRef = new AtomicReference<>(0.0);

        // ExecutorService para rodar múltiplas iterações em paralelo
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);

        // Envia várias "tarefas" de GRASP em paralelo.
        // Cada thread fica rodando até esgotar o tempo ou se for interrompida.
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < NUM_THREADS; t++) {
            Future<?> f = executor.submit(() -> {
                // Enquanto houver tempo, segue tentando gerar soluções
                while (true) {
                    long now = stopWatch.getTime();
                    if (now >= deadline) {
                        break; // tempo esgotou
                    }
                    // Executa uma iteração do GRASP+VND
                    ChallengeSolution candidate = buildGRASPSolution(stopWatch, deadline);
                    candidate = vndImprove(candidate, stopWatch, deadline);

                    // Calcula o valor-objetivo (razão totalUnidades / corredoresUsados)
                    double ratio = computeObjectiveFunction(candidate);
                    // Verifica se melhorou o global
                    synchronized (bestSolRef) {
                        if (ratio > bestRatioRef.get()) {
                            bestRatioRef.set(ratio);
                            bestSolRef.set(candidate);
                        }
                    }
                }
            });
            futures.add(f);
        }

        // Aguarda todos finalizarem ou tempo esgotar
        for (Future<?> f : futures) {
            try {
                f.get(); // se exceder o tempo, elas param naturalmente via verificação de deadline
            } catch (InterruptedException | ExecutionException e) {
                // Caso algum erro ocorra, interrompe
                e.printStackTrace();
            }
        }
        executor.shutdown();

        // Retorna melhor solução conhecida
        ChallengeSolution bestSol = bestSolRef.get();
        // Se não encontrou nada viável, tenta alguma fallback
        if (bestSol == null) {
            bestSol = buildTrivialSolution();
        }
        return bestSol;
    }

    /**
     * Constrói uma solução trivial viável (por exemplo, pega um pedido e
     * um corredor que o contenha, garantindo LB <= waveSize <= UB).
     * Apenas para fallback caso nada seja encontrado.
     */
    private ChallengeSolution buildTrivialSolution() {
        // Tenta selecionar um único pedido que caiba nos limites e achar corredores que o cubram
        for (int o = 0; o < orders.size(); o++) {
            int sumItems = sumOrder(orders.get(o));
            if (sumItems >= waveSizeLB && sumItems <= waveSizeUB) {
                // Precisamos encontrar corredores que cubram todos os itens desse pedido
                Set<Integer> aislesUsed = coverOrders(Collections.singleton(o));
                if (!aislesUsed.isEmpty()) {
                    return new ChallengeSolution(
                            new HashSet<>(Collections.singletonList(o)),
                            aislesUsed
                    );
                }
            }
        }
        // Se nada, retorna vazio (inviável, mas atende a interface)
        return new ChallengeSolution(new HashSet<>(), new HashSet<>());
    }

    /**
     * Uma iteração de GRASP: constrói solução de modo guloso-randomizado.
     * (Sem inserir local search aqui, pois faremos VND separadamente.)
     */
    private ChallengeSolution buildGRASPSolution(StopWatch sw, long deadline) {
        // Lista de pedidos candidatos
        List<Integer> allOrders = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            allOrders.add(i);
        }
        // Embaralha ou ordena de forma guloso-random. Exemplo: peso decrescente + random.
        // Vamos calcular "peso" = total de itens no pedido.
        // Em seguida, definimos alpha random e construímos RCL (Candidate List Restrita).
        Map<Integer, Integer> orderToWeight = new HashMap<>();
        for (int i : allOrders) {
            orderToWeight.put(i, sumOrder(orders.get(i)));
        }

        // Solução parcial
        Set<Integer> chosenOrders = new HashSet<>();
        int currentSumItems = 0;

        // Precisamos de um loop até atingirmos LB ou esgotar pedidos
        // mas também tomar cuidado com UB.
        // GRASP clássico: enquanto houver pedidos que caibam e wave < LB, continue.
        // Se wave >= LB, pode parar ou tentar inserir mais se couber. Faremos heurística.
        while (!allOrders.isEmpty()) {
            long now = sw.getTime();
            if (now >= deadline) {
                break; // acabou tempo
            }
            // Calcula min e max
            int minW = Integer.MAX_VALUE, maxW = Integer.MIN_VALUE;
            for (int ord : allOrders) {
                int w = orderToWeight.get(ord);
                if (w < minW) minW = w;
                if (w > maxW) maxW = w;
            }
            if (maxW < 1) {
                // caso extremo: não há nada relevante
                break;
            }
            double alpha = Math.random(); // ex: alpha random entre [0..1]
            double threshold = maxW - alpha * (maxW - minW);

            // Monta a RCL: pedidos cujo "peso" >= threshold
            List<Integer> RCL = new ArrayList<>();
            for (int ord : allOrders) {
                int w = orderToWeight.get(ord);
                if (w >= threshold) {
                    RCL.add(ord);
                }
            }
            if (RCL.isEmpty()) {
                // nenhuma RCL, sai
                break;
            }
            // Escolhe 1 pedido aleatório da RCL
            int chosen = RCL.get(new Random().nextInt(RCL.size()));

            int wChosen = orderToWeight.get(chosen);
            // Se colocar este pedido estoura o UB, talvez não devêssemos colocá-lo.
            if (currentSumItems + wChosen > waveSizeUB) {
                // ou paramos a inserção
                // p.ex. paramos se já estamos >= LB
                if (currentSumItems >= waveSizeLB) {
                    break;
                } else {
                    // remover esse pedido da lista e continuar
                    allOrders.remove((Integer) chosen);
                    continue;
                }
            }
            // Se coube, coloca
            chosenOrders.add(chosen);
            currentSumItems += wChosen;
            // remove da lista
            allOrders.remove((Integer) chosen);

            // Se já passamos do LB, podemos encerrar ou tentar seguir
            // para este exemplo, continuamos até não haver mais "moves" viáveis ou esgotar a lista
        }

        // Se não atingimos LB, a solução pode ficar inviável. Vamos ver:
        if (currentSumItems < waveSizeLB) {
            // falhou a construção? Devolvemos algo possivelmente vazio
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }

        // Precisamos escolher os corredores que cubram todos os itens dos pedidos
        // (mínimo conjunto de corredores que supra as quantidades).
        Set<Integer> chosenAisles = coverOrders(chosenOrders);
        // Monta e retorna
        return new ChallengeSolution(chosenOrders, chosenAisles);
    }

    /**
     * Gera (heuristicamente) um conjunto de corredores que cubram
     * as necessidades dos pedidos "chosenOrders".
     * Este método faz uma aproximação de "set cover" multi-conjunto,
     * pois precisamos garantir que a soma de u_ai nos corredores
     * seja >= soma de u_oi para cada item i.
     */
    private Set<Integer> coverOrders(Set<Integer> chosenOrders) {
        // Soma de demanda por item
        int[] demandItem = new int[nItems];
        for (int o : chosenOrders) {
            Map<Integer, Integer> mapa = orders.get(o);
            for (Map.Entry<Integer, Integer> e : mapa.entrySet()) {
                demandItem[e.getKey()] += e.getValue();
            }
        }
        // Se não tem demanda, retorna vazio
        int totalDemanda = 0;
        for (int d : demandItem) {
            totalDemanda += d;
        }
        if (totalDemanda == 0) {
            return new HashSet<>();
        }

        // Vamos usar uma heurística gulosa para cobrir:
        // 1) Montar lista de corredores e seu "potencial" de cobertura (soma que ele pode cobrir).
        // 2) Repetir até cobrir todas as demandas: escolhe o corredor que melhor reduz a falta.
        //    e atualiza as demandas.
        //
        // Observação: essa heurística não é perfeita, mas rápida e funciona bem.
        Set<Integer> usedAisles = new HashSet<>();
        int[] remain = Arrays.copyOf(demandItem, demandItem.length);

        while (true) {
            // Verifica se já está tudo coberto
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
            // Se não, escolhe corredor
            int bestAisle = -1;
            int bestCover = 0;
            for (int a = 0; a < aisles.size(); a++) {
                if (usedAisles.contains(a)) continue;
                // calcula quanto esse corredor pode ajudar
                int potential = 0;
                for (Map.Entry<Integer, Integer> e : aisles.get(a).entrySet()) {
                    int itemId = e.getKey();
                    int qty = e.getValue();
                    // só contribui se remain[itemId] > 0
                    if (remain[itemId] > 0) {
                        // cobre min(remain, qty)
                        potential += Math.min(remain[itemId], qty);
                    }
                }
                if (potential > bestCover) {
                    bestCover = potential;
                    bestAisle = a;
                }
            }
            // Se bestCover=0, significa que nenhum corredor adicional ajuda a cobrir
            // a parte que falta => impossibilidade. Então paramos e devolvemos todos mesmo.
            if (bestAisle < 0) {
                // nesse caso, use todos os corredores, garante factibilidade
                for (int i = 0; i < aisles.size(); i++) {
                    usedAisles.add(i);
                }
                break;
            } else {
                // Adiciona esse corredor
                usedAisles.add(bestAisle);
                // Subtrai do remain
                for (Map.Entry<Integer, Integer> e : aisles.get(bestAisle).entrySet()) {
                    int itemId = e.getKey();
                    int qty = e.getValue();
                    if (remain[itemId] > 0) {
                        remain[itemId] -= Math.min(remain[itemId], qty);
                    }
                }
            }
        }

        return usedAisles;
    }

    /**
     * Busca local do tipo VND sobre o conjunto de pedidos (mantendo a escolha de corredores recalculada a cada modificação).
     *
     * N1: "Inserir" (se não está) ou "Remover" (se está) algum pedido? (poderia chamar de Insert/Remove)
     * N2: "Trocar" dois pedidos? (swap simples)
     * N3: "Trocar 2 pedidos por 1" ou vice-versa (para manipular a soma LB e UB).
     *
     * Aqui simplificamos para: remover 1 pedido ou adicionar 1 pedido ou trocar 1x1. 
     * Recalcula set de corredores e checa se ratio melhorou.
     */
    private ChallengeSolution vndImprove(ChallengeSolution initialSol, StopWatch sw, long deadline) {
        ChallengeSolution best = initialSol;
        double bestRatio = computeObjectiveFunction(best);

        int kMax = 3;
        int k = 1;

        while (k <= kMax) {
            long now = sw.getTime();
            if (now >= deadline) {
                break; // tempo acabou
            }
            ChallengeSolution newSol = null;
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
            }
            double newRatio = computeObjectiveFunction(newSol);
            if (newRatio > bestRatio) {
                best = newSol;
                bestRatio = newRatio;
                k = 1; // volta para primeira vizinhança
            } else {
                k++;
            }
        }

        return best;
    }

    /**
     * Vizinhança 1 (k=1): Insert/Remove
     * Tenta remover algum pedido do conjunto ou adicionar algum pedido fora do conjunto,
     * se ficar factível e melhorar ratio.
     */
    private ChallengeSolution neighborhoodInsertRemove(ChallengeSolution current, StopWatch sw, long deadline) {
        Set<Integer> baseOrders = new HashSet<>(current.orders());
        double baseRatio = computeObjectiveFunction(current);
        ChallengeSolution bestLocal = current;

        // Preparar lista de todos pedidos
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            all.add(i);
        }

        // Tenta remover cada pedido (um de cada vez):
        for (int rem : current.orders()) {
            long now = sw.getTime();
            if (now >= deadline) break;

            Set<Integer> newOrders = new HashSet<>(baseOrders);
            newOrders.remove(rem);
            int sumItems = sumOrders(newOrders);
            if (sumItems >= waveSizeLB && sumItems <= waveSizeUB) {
                // recalcula corredores
                Set<Integer> cover = coverOrders(newOrders);
                ChallengeSolution candidate = new ChallengeSolution(newOrders, cover);
                double ratio = computeObjectiveFunction(candidate);
                if (ratio > baseRatio) {
                    baseRatio = ratio;
                    bestLocal = candidate;
                }
            }
        }

        // Tenta adicionar algum pedido que não está no current
        for (int add : all) {
            if (baseOrders.contains(add)) continue;
            long now = sw.getTime();
            if (now >= deadline) break;

            int wAdd = sumOrder(orders.get(add));
            int sumItems = sumOrders(baseOrders);
            if (sumItems + wAdd > waveSizeUB) {
                continue; // não cabe
            }
            Set<Integer> newOrders = new HashSet<>(baseOrders);
            newOrders.add(add);
            int newSum = sumItems + wAdd;
            if (newSum >= waveSizeLB && newSum <= waveSizeUB) {
                // recalcula corredores
                Set<Integer> cover = coverOrders(newOrders);
                ChallengeSolution candidate = new ChallengeSolution(newOrders, cover);
                double ratio = computeObjectiveFunction(candidate);
                if (ratio > baseRatio) {
                    baseRatio = ratio;
                    bestLocal = candidate;
                }
            }
        }

        return bestLocal;
    }

    /**
     * Vizinhança 2 (k=2): Swap 1x1
     * Trocar um pedido do conjunto por outro fora do conjunto.
     */
    private ChallengeSolution neighborhoodSwap1x1(ChallengeSolution current, StopWatch sw, long deadline) {
        Set<Integer> baseOrders = new HashSet<>(current.orders());
        double baseRatio = computeObjectiveFunction(current);
        ChallengeSolution bestLocal = current;

        // criar lista (dentro) e (fora)
        List<Integer> inside = new ArrayList<>(baseOrders);
        List<Integer> outside = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (!baseOrders.contains(i)) outside.add(i);
        }
        int sumInside = sumOrders(baseOrders);

        for (int in : inside) {
            int wIn = sumOrder(orders.get(in));
            for (int out : outside) {
                long now = sw.getTime();
                if (now >= deadline) break;
                int wOut = sumOrder(orders.get(out));

                int newSum = sumInside - wIn + wOut;
                if (newSum < waveSizeLB || newSum > waveSizeUB) {
                    continue; // inviável
                }
                // faz swap
                Set<Integer> newSet = new HashSet<>(baseOrders);
                newSet.remove(in);
                newSet.add(out);
                // recalcula corredores
                Set<Integer> cover = coverOrders(newSet);
                ChallengeSolution candidate = new ChallengeSolution(newSet, cover);
                double ratio = computeObjectiveFunction(candidate);
                if (ratio > baseRatio) {
                    baseRatio = ratio;
                    bestLocal = candidate;
                }
            }
        }
        return bestLocal;
    }

    /**
     * Vizinhança 3 (k=3): Swap 2x1
     * Remove dois pedidos de inside e adiciona 1 de fora, ou vice-versa.
     * Para simplificar, faremos apenas remove 2 + add 1 (caso recupere espaço para ficar no LB/UB).
     */
    private ChallengeSolution neighborhoodSwap2x1(ChallengeSolution current, StopWatch sw, long deadline) {
        Set<Integer> baseOrders = new HashSet<>(current.orders());
        double baseRatio = computeObjectiveFunction(current);
        ChallengeSolution bestLocal = current;

        List<Integer> inside = new ArrayList<>(baseOrders);
        List<Integer> outside = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (!baseOrders.contains(i)) outside.add(i);
        }
        int sumInside = sumOrders(baseOrders);

        // Tenta remove 2 e add 1
        for (int i = 0; i < inside.size(); i++) {
            for (int j = i + 1; j < inside.size(); j++) {
                long now = sw.getTime();
                if (now >= deadline) break;

                int o1 = inside.get(i), o2 = inside.get(j);
                int w1 = sumOrder(orders.get(o1));
                int w2 = sumOrder(orders.get(o2));
                int newSumBase = sumInside - w1 - w2;

                // Se com esses 2 removidos já não ficamos abaixo do LB
                if (newSumBase < 0) continue;

                for (int out : outside) {
                    long now2 = sw.getTime();
                    if (now2 >= deadline) break;

                    int wOut = sumOrder(orders.get(out));
                    int candidateSum = newSumBase + wOut;
                    if (candidateSum >= waveSizeLB && candidateSum <= waveSizeUB) {
                        // gerar a nova sol
                        Set<Integer> newSet = new HashSet<>(baseOrders);
                        newSet.remove(o1);
                        newSet.remove(o2);
                        newSet.add(out);
                        Set<Integer> cover = coverOrders(newSet);
                        ChallengeSolution candidate = new ChallengeSolution(newSet, cover);
                        double ratio = computeObjectiveFunction(candidate);
                        if (ratio > baseRatio) {
                            baseRatio = ratio;
                            bestLocal = candidate;
                        }
                    }
                }
            }
        }

        return bestLocal;
    }

    /**
     * Calcula a soma total de itens em um map (item->quantidade).
     */
    private int sumOrder(Map<Integer, Integer> orderMap) {
        int s = 0;
        for (int v : orderMap.values()) {
            s += v;
        }
        return s;
    }

    /**
     * Soma total de itens de um conjunto de pedidos
     */
    private int sumOrders(Set<Integer> ordersSelected) {
        int total = 0;
        for (int o : ordersSelected) {
            total += sumOrder(orders.get(o));
        }
        return total;
    }

    /**
     * Função objetivo: total de itens coletados / quantidade de corredores selecionados.
     * Se corredores=0, definimos ratio=0 para evitar divisão por zero.
     */
    protected double computeObjectiveFunction(ChallengeSolution sol) {
        if (sol == null || sol.orders() == null || sol.aisles() == null) return 0.0;
        if (sol.aisles().isEmpty()) {
            // se não há corredores, ratio=0 para evitar exceção
            return 0.0;
        }
        // Soma total de itens
        int sum = sumOrders(sol.orders());
        return (double) sum / sol.aisles().size();
    }

    /**
     * Retorna o tempo remanescente (em segundos) para uso interno, se precisar.
     * (Não está sendo essencial nesta implementação, mas fica aqui para referência.)
     */
    protected long getRemainingTime(StopWatch stopWatch) {
        long elapsedMs = stopWatch.getTime();
        long remainMs = (MAX_RUNTIME - elapsedMs);
        if (remainMs < 0) return 0;
        return TimeUnit.SECONDS.convert(remainMs, TimeUnit.MILLISECONDS);
    }

}
