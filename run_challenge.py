import os
import subprocess
import sys
import platform

# Paths to the libraries
CPLEX_PATH = "$HOME/CPLEX_Studio2211/opl/bin/arm64_osx/"
OR_TOOLS_PATH = "$HOME/Documents/or-tools/build/lib/"

USE_CPLEX = True
USE_OR_TOOLS = True

MAX_RUNNING_TIME = "605s"

def compile_code(source_folder):
    print(f"Compiling code in {source_folder}...")
    # Change to the source folder
    os.chdir(source_folder)

    # Run Maven compile
    result = subprocess.run(["mvn", "clean", "package"], capture_output=True, text=True)

    if result.returncode != 0:
        print("Maven compilation failed:")
        print(result.stderr)
        return False

    print("Maven compilation successful.")
    return True


def run_benchmark(source_folder, input_folder, output_folder):
    # Change to the source folder
    os.chdir(source_folder)

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    # Set the library path (if needed)
    if USE_CPLEX and USE_OR_TOOLS:
        libraries = f"{OR_TOOLS_PATH}:{CPLEX_PATH}"
    elif USE_CPLEX:
        libraries = CPLEX_PATH
    elif USE_OR_TOOLS:
        libraries = OR_TOOLS_PATH

    if platform.system() == "Darwin":
        timeout_command = "gtimeout"
    else:
        timeout_command = "timeout"

    for filename in os.listdir(input_folder):
        if filename.endswith(".txt"):
            print(f"Running {filename}")
            input_file = os.path.join(input_folder, filename)
            output_file = os.path.join(output_folder, f"{os.path.splitext(filename)[0]}.txt")
            with open(output_file, "w") as out:
                # Main Java command
                cmd = [timeout_command, MAX_RUNNING_TIME, "java", "-Xmx16g", "-jar", "target/ChallengeSBPO2025-1.0.jar",
                       input_file,
                       output_file]
                if USE_CPLEX or USE_OR_TOOLS:
                    cmd.insert(3, f"-Djava.library.path={libraries}")

                result = subprocess.run(cmd, stderr=subprocess.PIPE, text=True)
                if result.returncode != 0:
                    print(f"Execution failed for {input_file}:")
                    print(result.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python run_challenge.py <source_folder> <input_folder> <output_folder>")
        sys.exit(1)

    source_folder = sys.argv[1]
    input_folder = sys.argv[2]
    output_folder = sys.argv[3]

    if compile_code(source_folder):
        run_benchmark(source_folder, input_folder, output_folder)
