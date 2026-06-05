import os
import hvac

class Config:
    def __init__(self):
        # Default fallbacks
        self.RAG_ENGINE_URL = os.getenv("RAG_ENGINE_URL", "http://localhost:8000")
        self.REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
        self.REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
        self.REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")
        self.VAULT_ENABLED = os.getenv("VAULT_ENABLED", "true").lower() == "true"
        
        # Load from Vault if enabled
        if self.VAULT_ENABLED:
            self.load_from_vault()

    def load_from_vault(self):
        vault_addr = os.getenv("VAULT_ADDR", "http://vault:8200")
        vault_token = os.getenv("VAULT_TOKEN", "root-token")
        secret_path = os.getenv("VAULT_SECRET_PATH", "llmops-platform")
        
        print(f"Connecting to Vault at {vault_addr} to fetch secrets...")
        try:
            client = hvac.Client(url=vault_addr, token=vault_token)
            if client.is_authenticated():
                print("Successfully authenticated with HashiCorp Vault.")
                # Read kv-v2 secret
                secret_response = client.secrets.kv.v2.read_secret_version(
                    path=secret_path,
                    mount_point="secret"
                )
                secrets = secret_response["data"]["data"]
                
                # Update configurations
                if "RAG_ENGINE_URL" in secrets:
                    self.RAG_ENGINE_URL = secrets["RAG_ENGINE_URL"]
                if "REDIS_HOST" in secrets:
                    self.REDIS_HOST = secrets["REDIS_HOST"]
                if "REDIS_PORT" in secrets:
                    self.REDIS_PORT = int(secrets["REDIS_PORT"])
                if "REDIS_PASSWORD" in secrets:
                    self.REDIS_PASSWORD = secrets["REDIS_PASSWORD"]
                    
                print("Successfully loaded secrets from Vault.")
            else:
                print("Vault authentication failed. Falling back to environment variables.")
        except Exception as e:
            print(f"Failed to connect to Vault ({e}). Falling back to environment variables.")

config = Config()
