import os
import subprocess
import shutil
import rarfile

# Set the path to the RAR file
rar_path = 'attached_assets/Drowsiness-Detection-Android-App-main.rar'
extract_path = 'extracted_project'

# Make sure the extraction directory exists and is empty
if os.path.exists(extract_path):
    shutil.rmtree(extract_path)
os.makedirs(extract_path, exist_ok=True)

try:
    # Try using native rarfile python library first
    print(f"Extracting {rar_path} to {extract_path} using rarfile...")
    try:
        with rarfile.RarFile(rar_path) as rf:
            rf.extractall(extract_path)
        print("Extraction completed successfully with rarfile.")
    except Exception as rar_error:
        print(f"Rarfile extraction failed: {str(rar_error)}")
        print("Falling back to 7z extraction...")
        
        # Fallback to using 7z command
        result = subprocess.run(['7z', 'x', rar_path, f'-o{extract_path}'], 
                               capture_output=True, text=True)
        
        if result.returncode == 0:
            print("Extraction completed successfully with 7z.")
        else:
            print(f"Extraction failed with error code {result.returncode}")
            print(f"Error output: {result.stderr}")
            exit(1)
        
    # Print the extracted contents
    print("\nContents:")
    for root, dirs, files in os.walk(extract_path):
        level = root.replace(extract_path, '').count(os.sep)
        indent = ' ' * 4 * level
        print(f"{indent}{os.path.basename(root)}/")
        sub_indent = ' ' * 4 * (level + 1)
        for f in files:
            print(f"{sub_indent}{f}")
            
    # Copy all content from extracted_project to the main project directory
    print("\nCopying extracted content to main project folder...")
    source_dir = os.path.join(extract_path)
    app_dir = None
    
    # Find the main app directory inside the extracted content
    for item in os.listdir(source_dir):
        full_path = os.path.join(source_dir, item)
        if os.path.isdir(full_path):
            # Check if this looks like an Android project directory
            if os.path.exists(os.path.join(full_path, 'app')) or \
               os.path.exists(os.path.join(full_path, 'build.gradle')):
                app_dir = full_path
                break
    
    if app_dir:
        print(f"Found main project directory: {app_dir}")
        
        # Copy top-level files to project root
        for item in os.listdir(app_dir):
            src_path = os.path.join(app_dir, item)
            dst_path = os.path.join('.', item)
            
            # Only copy if it doesn't exist already or is a directory
            if not os.path.exists(dst_path) or os.path.isdir(src_path):
                if os.path.isdir(src_path):
                    if os.path.exists(dst_path):
                        shutil.rmtree(dst_path)
                    shutil.copytree(src_path, dst_path)
                    print(f"Copied directory: {item}")
                else:
                    shutil.copy2(src_path, dst_path)
                    print(f"Copied file: {item}")

        # Make gradlew executable
        gradlew_path = os.path.join('.', 'gradlew')
        if os.path.exists(gradlew_path):
            os.chmod(gradlew_path, 0o755)
            print("Made gradlew executable")
    else:
        print("Could not find main project directory in extracted content.")
        
except Exception as e:
    print(f"An error occurred: {str(e)}")
    exit(1)