import urllib.request
import os

# Create directory if it doesn't exist
os.makedirs('gradle/wrapper', exist_ok=True)

# URL for gradle-wrapper.jar
url = "https://github.com/gradle/gradle/raw/v7.5.0/gradle/wrapper/gradle-wrapper.jar"

# Download the file
print("Downloading gradle-wrapper.jar...")
urllib.request.urlretrieve(url, 'gradle/wrapper/gradle-wrapper.jar')
print("Download complete!")