import os
import time
from multiprocessing import Pool
import pulp

# Função para ler os dados da instância
def read_instance(file_path):
    """
    Lê os dados do arquivo de entrada, como pedidos, corredores e limites da wave.
    
    Parâmetros:
    file_path (str): Caminho do arquivo de entrada (ex: 'instance_0001.txt').
    
    Retorna:
    tuple: (orders, aisles, n_items, wave_size_lb, wave_size_ub)
    """
    with open(file_path, 'r') as f:
        lines = f.readlines()
        # Primeira linha: número de pedidos, itens e corredores
        first_line = lines[0].strip().split()
        n_orders, n_items, n_aisles = int(first_line[0]), int(first_line[1]), int(first_line[2])
        
        # Ler pedidos: cada pedido é um dicionário {item: quantidade}
        orders = []
        for i in range(1, n_orders + 1):
            line = lines[i].strip().split()
            d = int(line[0])  # Quantidade de itens no pedido
            order = {int(line[2*k+1]): int(line[2*k+2]) for k in range(d)}
            orders.append(order)
        
        # Ler corredores: cada corredor é um dicionário {item: quantidade}
        aisles = []
        for i in range(n_orders + 1, n_orders + n_aisles + 1):
            line = lines[i].strip().split()
            d = int(line[0])  # Quantidade de itens no corredor
            aisle = {int(line[2*k+1]): int(line[2*k+2]) for k in range(d)}
            aisles.append(aisle)
        
        # Última linha: limites inferior (LB) e superior (UB) da wave
        bounds = lines[n_orders + n_aisles + 1].strip().split()
        wave_size_lb, wave_size_ub = int(bounds[0]), int(bounds[1])
        
    return orders, aisles, n_items, wave_size_lb, wave_size_ub

# Função para salvar a solução
def write_output(output_file_path, selected_orders, selected_aisles):
    """
    Escreve a solução no arquivo de saída no formato esperado pelo checker.
    
    Parâmetros:
    output_file_path (str): Caminho do arquivo de saída.
    selected_orders (list): Índices dos pedidos selecionados.
    selected_aisles (list): Índices dos corredores selecionados.
    """
    with open(output_file_path, 'w') as f:
        f.write(f"{len(selected_orders)}\n")  # Número de pedidos
        for o in selected_orders:
            f.write(f"{o}\n")  # Índices dos pedidos
        f.write(f"{len(selected_aisles)}\n")  # Número de corredores
        for a in selected_aisles:
            f.write(f"{a}\n")  # Índices dos corredores

# Função principal para resolver uma instância
def solve_instance(instance_file):
    """
    Resolve uma instância do problema maximizando a produtividade com PuLP/CBC.
    
    Parâmetros:
    instance_file (str): Nome do arquivo da instância (ex: 'instance_0001.txt').
    """
    start_time = time.time()  # Marca o início do processamento
    input_path = os.path.join('datasets/a', instance_file)  # Caminho de entrada
    output_path = os.path.join('output', instance_file)     # Caminho de saída
    
    # Ler os dados da instância
    orders, aisles, n_items, wave_size_lb, wave_size_ub = read_instance(input_path)
    
    # Pré-processamento: calcula o total de unidades por pedido
    total_units = [sum(order.values()) for order in orders]
    
    # Mapeia itens para pedidos e corredores (para restrições de disponibilidade)
    item_to_orders = {i: [] for i in range(n_items)}
    for o, order in enumerate(orders):
        for i, qty in order.items():
            item_to_orders[i].append((o, qty))  # (pedido, quantidade)
    
    item_to_aisles = {i: [] for i in range(n_items)}
    for a, aisle in enumerate(aisles):
        for i, qty in aisle.items():
            item_to_aisles[i].append((a, qty))  # (corredor, quantidade)
    
    # Testar diferentes valores de k (número de corredores)
    best_ratio = 0
    best_solution = None
    k_max = len(aisles)
    
    for k in range(1, k_max + 1):
        # Criar um novo modelo PuLP para cada k
        model = pulp.LpProblem(f"WavePicking_{instance_file}_k{k}", pulp.LpMaximize)
        
        # Variáveis binárias
        x = pulp.LpVariable.dicts("x", range(len(orders)), cat=pulp.LpBinary)  # 1 se pedido o é selecionado
        y = pulp.LpVariable.dicts("y", range(len(aisles)), cat=pulp.LpBinary)  # 1 se corredor a é selecionado
        
        # Restrição de tamanho da wave
        model += pulp.lpSum(total_units[o] * x[o] for o in range(len(orders))) >= wave_size_lb, "WaveLB"
        model += pulp.lpSum(total_units[o] * x[o] for o in range(len(orders))) <= wave_size_ub, "WaveUB"
        
        # Restrições de disponibilidade de itens
        for i in range(n_items):
            if item_to_orders[i]:  # Apenas para itens presentes em pedidos
                lhs = pulp.lpSum(u_oi * x[o] for o, u_oi in item_to_orders[i])  # Demanda do item i
                rhs = pulp.lpSum(u_ai * y[a] for a, u_ai in item_to_aisles[i])  # Oferta do item i
                model += lhs <= rhs, f"Availability_{i}"
        
        # Limite no número de corredores
        model += pulp.lpSum(y[a] for a in range(len(aisles))) <= k, "AisleLimit"
        
        # Objetivo: maximizar o total de unidades
        model += pulp.lpSum(total_units[o] * x[o] for o in range(len(orders))), "TotalUnits"
        
        # Resolve o modelo com CBC
        model.solve(pulp.PULP_CBC_CMD(msg=0))  # Silencia a saída do solver
        
        if model.status == pulp.LpStatusOptimal:
            v_k = pulp.value(model.objective)  # Total de unidades
            ratio = v_k / k                    # Razão unidades/corredores
            elapsed_time = time.time() - start_time
            
            print(f"Instância {instance_file} | k={k} | Total unidades={v_k:.2f} | Razão={ratio:.2f} | "
                  f"Tempo={elapsed_time:.2f}s")
            
            if ratio > best_ratio:
                best_ratio = ratio
                selected_orders = [o for o in range(len(orders)) if x[o].value() > 0.5]
                selected_aisles = [a for a in range(len(aisles)) if y[a].value() > 0.5]
                best_solution = (selected_orders, selected_aisles)
                print(f"** Nova melhor solução para {instance_file} | Razão={best_ratio:.2f} | "
                      f"Pedidos={len(selected_orders)} | Corredores={len(selected_aisles)} | "
                      f"Tempo={elapsed_time:.2f}s **")
        else:
            print(f"Instância {instance_file} | k={k} | Sem solução viável")
    
    # Salvar a melhor solução
    if best_solution:
        selected_orders, selected_aisles = best_solution
        write_output(output_path, selected_orders, selected_aisles)
        print(f"Instância {instance_file} concluída | Melhor razão={best_ratio:.2f} | "
              f"Tempo total={time.time() - start_time:.2f}s")

# Função principal
if __name__ == "__main__":
    if not os.path.exists('output'):
        os.makedirs('output')  # Cria pasta de saída
    
    # Lista todas as instâncias
    instance_files = [f for f in os.listdir('datasets/a') if f.startswith('instance_') and f.endswith('.txt')]
    instance_files.sort()
    
    print(f"Total de instâncias a processar: {len(instance_files)}")
    
    # Processa em paralelo
    with Pool(processes=os.cpu_count()) as pool:
        pool.map(solve_instance, instance_files)
    
    print("Todas as instâncias foram processadas. Resultados salvos em 'output'.")