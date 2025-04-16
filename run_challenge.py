import os
import subprocess
import sys
import platform
import signal
from subprocess import Popen, PIPE

# Paths to the libraries
CPLEX_PATH = "/home/scarletflexsim/cplex/cplex/bin/x86-64_linux"
OR_TOOLS_PATH = "C:\\Program Files\\or-tools\\lib"

USE_CPLEX = True 
USE_OR_TOOLS = False  

MAX_RUNNING_TIME = 605  # em segundos

def compile_code(source_folder):
    print(f"Compilando código em {source_folder}...")
    # Change to the source folder
    os.chdir(source_folder)

    # Use the full path to Maven
    mvn_cmd = "mvn"
    
    # Run Maven compile
    result = subprocess.run([mvn_cmd, "clean", "package"], capture_output=True, text=True)

    if result.returncode != 0:
        print("Falha na compilação Maven:")
        print(result.stderr)
        return False

    print("Compilação Maven bem sucedida.")
    return True

def run_with_timeout(cmd, timeout_sec):
    process = Popen(cmd, stdout=PIPE, stderr=PIPE)
    try:
        stdout, stderr = process.communicate(timeout=timeout_sec)
        return process.returncode, stdout, stderr
    except subprocess.TimeoutExpired:
        process.kill()
        print(f"Processo encerrado após {timeout_sec} segundos")
        return -1, b"", b"Timeout"

def run_benchmark(source_folder, input_folder, output_folder):
    # Change to the source folder
    os.chdir(source_folder)

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    # Set the library path (if needed)
    if USE_CPLEX and USE_OR_TOOLS:
        libraries = f"{OR_TOOLS_PATH};{CPLEX_PATH}"
    elif USE_CPLEX:
        libraries = CPLEX_PATH
    elif USE_OR_TOOLS:
        libraries = OR_TOOLS_PATH

    for filename in os.listdir(input_folder):
        if filename.endswith(".txt"):
            print(f"Executando {filename}")
            input_file = os.path.join(input_folder, filename)
            output_file = os.path.join(output_folder, f"{os.path.splitext(filename)[0]}.txt")
            with open(output_file, "w") as out:
                # Main Java command
                cmd = ["java", "-Xmx16g", "-jar", "target/ChallengeSBPO2025-1.0.jar",
                      input_file,
                      output_file]
                if USE_CPLEX or USE_OR_TOOLS:
                    cmd.insert(1, f"-Djava.library.path={libraries}")

                returncode, stdout, stderr = run_with_timeout(cmd, MAX_RUNNING_TIME)
                if returncode != 0:
                    print(f"Falha na execução para {input_file}:")
                    print(stderr.decode('utf-8', errors='ignore'))

if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Uso: python run_challenge.py <pasta_origem> <pasta_entrada> <pasta_saida>")
        sys.exit(1)

    source_folder = sys.argv[1]
    input_folder = sys.argv[2]
    output_folder = sys.argv[3]

    if compile_code(source_folder):
        run_benchmark(source_folder, input_folder, output_folder)