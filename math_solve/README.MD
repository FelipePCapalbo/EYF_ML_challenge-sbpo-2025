# Modelagem Matemática do Problema de Seleção de Pedidos Ótima

Este documento descreve a modelagem matemática utilizada para resolver o problema de **Seleção de Pedidos Ótima** proposto no desafio SBPO 2025. O objetivo é maximizar a produtividade da coleta de pedidos em um armazém, selecionando um subconjunto de pedidos (denominado *wave*) e um subconjunto de corredores, de modo que a razão entre o total de unidades coletadas e o número de corredores visitados seja maximizada, respeitando as restrições operacionais.

---

## 1. Descrição do Problema

O problema envolve escolher:
- Um subconjunto de pedidos \( O' \), onde \( O' \subset O \) (sendo \( O \) o conjunto de todos os pedidos disponíveis no *backlog*);
- Um subconjunto de corredores \( A' \), onde \( A' \subset A \) (sendo \( A \) o conjunto de todos os corredores do armazém).

O objetivo é maximizar a produtividade, definida como:

\[
\text{max} \frac{\sum_{o \in O'} \sum_{i \in I_o} u_{oi}}{|A'|}
\]

Onde:
- \( I_o \): conjunto de itens no pedido \( o \);
- \( u_{oi} \): número de unidades do item \( i \) solicitado pelo pedido \( o \);
- \( |A'| \): número de corredores selecionados.

### Restrições Operacionais
1. **Limites do Tamanho da Wave**:
   \[
   \text{LB} \leq \sum_{o \in O'} \sum_{i \in I_o} u_{oi} \leq \text{UB}
   \]
   O total de unidades coletadas nos pedidos selecionados deve estar entre um limite inferior (\(\text{LB}\)) e um limite superior (\(\text{UB}\)).

2. **Disponibilidade de Itens**:
   \[
   \sum_{o \in O'} u_{oi} \leq \sum_{a \in A'} u_{ai}, \quad \forall i \in I
   \]
   Onde \( u_{ai} \) representa o número de unidades do item \( i \) disponíveis no corredor \( a \), e \( I \) é o conjunto de todos os itens. Essa restrição garante que a demanda de cada item nos pedidos selecionados não exceda a oferta disponível nos corredores escolhidos.

---

## 2. Abordagem de Modelagem

O objetivo do problema é maximizar uma razão (unidades por corredor), o que caracteriza uma função objetivo não-linear. Para resolver isso utilizando técnicas de programação linear inteira (MILP), adotamos a seguinte estratégia:

- **Fixar o número de corredores \( k = |A'| \)** e maximizar o total de unidades coletadas (\( \sum_{o \in O'} \sum_{i \in I_o} u_{oi} \));
- **Testar diferentes valores de \( k \)**, variando de 1 até o número total de corredores (\( |A| \));
- **Calcular a razão para cada \( k \)** e selecionar o valor de \( k \) que maximiza a produtividade.

Essa abordagem transforma o problema original em uma série de problemas MILP lineares, que podem ser resolvidos eficientemente por solvers como Gurobi ou CBC.

---

## 3. Formulação Matemática

Para cada valor fixo de \( k \), o problema é formulado como um MILP com as seguintes características:

### Variáveis de Decisão
- \( x_o \in \{0,1\} \): indica se o pedido \( o \) é selecionado (\( x_o = 1 \)) ou não (\( x_o = 0 \));
- \( y_a \in \{0,1\} \): indica se o corredor \( a \) é selecionado (\( y_a = 1 \)) ou não (\( y_a = 0 \)).

### Função Objetivo
\[
\text{max} \sum_{o \in O} \sum_{i \in I_o} u_{oi} \cdot x_o
\]
Maximiza o total de unidades coletadas nos pedidos selecionados, dado um número fixo de corredores \( k \).

### Restrições
1. **Limites do Tamanho da Wave**:
   \[
   \text{LB} \leq \sum_{o \in O} \sum_{i \in I_o} u_{oi} \cdot x_o \leq \text{UB}
   \]
   Garante que o total de unidades coletadas esteja dentro dos limites operacionais definidos.

2. **Disponibilidade de Itens**:
   \[
   \sum_{o \in O} u_{oi} \cdot x_o \leq \sum_{a \in A} u_{ai} \cdot y_a, \quad \forall i \in I
   \]
   Assegura que a quantidade demandada de cada item \( i \) pelos pedidos selecionados não exceda a quantidade disponível nos corredores selecionados.

3. **Limite no Número de Corredores**:
   \[
   \sum_{a \in A} y_a \leq k
   \]
   Restringe o número de corredores selecionados a no máximo \( k \).

### Pós-processamento
Após resolver o MILP para um dado \( k \), calcula-se a razão de produtividade:
\[
\text{ratio}_k = \frac{\text{total de unidades coletadas}}{k}
\]
Testamos todos os valores possíveis de \( k \) (de 1 até \( |A| \)) e escolhemos o \( k \) que resulta na maior razão.

---

## 4. Detalhes da Implementação

A modelagem matemática descrita foi implementada em código, seguindo estas etapas:

### 4.1. Estrutura Geral
- **Entrada**: Lê os dados da instância (pedidos, corredores, itens, quantidades \( u_{oi} \) e \( u_{ai} \), e limites \(\text{LB}\) e \(\text{UB}\)).
- **Saída**: Gera a lista de pedidos e corredores selecionados que maximizam a produtividade.

### 4.2. Resolução para Cada \( k \)
- Para cada \( k \) de 1 até \( |A| \):
  1. Configura o modelo MILP com as variáveis \( x_o \) e \( y_a \), a função objetivo e as restrições descritas.
  2. Resolve o problema utilizando um solver de otimização (como Gurobi ou CBC).
  3. Calcula a razão \( \text{ratio}_k \) com base no total de unidades coletadas e no valor de \( k \).
  4. Armazena a solução se a razão for a melhor encontrada até o momento.

### 4.3. Seleção da Solução Final
- Após explorar todos os valores de \( k \), seleciona a solução com a maior razão \( \text{ratio}_k \), retornando os pedidos \( O' \) e corredores \( A' \) correspondentes.

---

## 5. Considerações Finais

- **Linearização**: A transformação do problema não-linear em uma série de problemas lineares permite o uso de solvers MILP robustos, garantindo soluções ótimas para cada \( k \).
- **Escalabilidade**: Para instâncias com muitos corredores, testar todos os \( k \) pode ser custoso. Nesse caso, heurísticas ou limites práticos para \( k \) poderiam ser explorados.
- **Flexibilidade**: A modelagem é genérica e pode ser adaptada para diferentes solvers ou formatos de entrada, mantendo a lógica matemática intacta.

Essa abordagem garante que a solução atenda às restrições do problema e maximize a produtividade da coleta de pedidos de forma rigorosa e eficiente.