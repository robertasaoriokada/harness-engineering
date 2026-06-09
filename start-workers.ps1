# ============================================================
# start-workers.ps1
# Compila o projeto, envia worker.jar e prompts para as EC2
# e inicia o worker em cada uma.
# Uso: .\start-workers.ps1
# ============================================================

$KEY        = "C:\Users\roberta.o.cusinato\Downloads\aws-academy-key.pem"
$WORKER1    = "ec2-user@ec2-44-192-53-95.compute-1.amazonaws.com"
$WORKER2    = "ec2-user@ec2-3-237-4-25.compute-1.amazonaws.com"
$OLLAMA_IP  = "98.92.11.231"
$PROJECT    = "C:\Users\roberta.o.cusinato\Desktop\harnessOficial\harness-engineering"
$JAVA_HOME  = "C:\Program Files\zulu21.50.19-ca-jdk21.0.11-win_x64"

# ── Credenciais AWS ──────────────────────────────────────────
# Cole aqui as credenciais do AWS Academy (renovar a cada sessão)
$AWS_ACCESS_KEY_ID     = "SUA_ACCESS_KEY_ID"
$AWS_SECRET_ACCESS_KEY = "SUA_SECRET_ACCESS_KEY"
$AWS_SESSION_TOKEN     = "SEU_SESSION_TOKEN"

# ── Variáveis do Worker ──────────────────────────────────────
$SQS_QUEUE_URL   = "https://sqs.us-east-1.amazonaws.com/431722041854/fila-benchmark-harness"
$AWS_REGION      = "us-east-1"
$OLLAMA_URL      = "http://${OLLAMA_IP}:11434"
$OLLAMA_MODEL    = "llama3.2:3b"
$DYNAMODB_TABLE  = "benchmark-results"
$WORKER_THREADS  = "1"
$PROMPTS_DIR     = "/home/ec2-user/prompts"

# ── 1. Compilar o projeto ────────────────────────────────────
Write-Host "==> Compilando projeto..."
$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$env:PATH"

Push-Location $PROJECT
mvn clean package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Error "Falha na compilacao. Abortando."
    exit 1
}
Pop-Location
Write-Host "    Compilacao concluida."

# ── 2. Enviar arquivos para cada worker ──────────────────────
$SCP_OPTS = "-i `"$KEY`" -o StrictHostKeyChecking=no"
$JAR      = "$PROJECT\target\worker.jar"
$PROMPTS  = "$PROJECT\prompts"

foreach ($WORKER in @($WORKER1, $WORKER2)) {
    Write-Host ""
    Write-Host "==> Enviando arquivos para $WORKER ..."

    # Garante que o diretório de prompts existe
    ssh -i $KEY -o StrictHostKeyChecking=no $WORKER "mkdir -p ~/prompts"

    # Envia worker.jar
    scp -i $KEY -o StrictHostKeyChecking=no $JAR "${WORKER}:~/"

    # Envia arquivos de prompt
    scp -i $KEY -o StrictHostKeyChecking=no "$PROMPTS\zero-shot.txt"        "${WORKER}:~/prompts/"
    scp -i $KEY -o StrictHostKeyChecking=no "$PROMPTS\chain-of-thought.txt" "${WORKER}:~/prompts/"
    scp -i $KEY -o StrictHostKeyChecking=no "$PROMPTS\self-consistency.txt" "${WORKER}:~/prompts/"

    Write-Host "    Arquivos enviados."
}

# ── 3. Iniciar o worker remotamente ─────────────────────────
$REMOTE_CMD = @"
export AWS_ACCESS_KEY_ID='$AWS_ACCESS_KEY_ID'
export AWS_SECRET_ACCESS_KEY='$AWS_SECRET_ACCESS_KEY'
export AWS_SESSION_TOKEN='$AWS_SESSION_TOKEN'
export SQS_QUEUE_URL='$SQS_QUEUE_URL'
export AWS_REGION='$AWS_REGION'
export OLLAMA_URL='$OLLAMA_URL'
export OLLAMA_MODEL='$OLLAMA_MODEL'
export DYNAMODB_TABLE='$DYNAMODB_TABLE'
export WORKER_THREADS='$WORKER_THREADS'
export PROMPTS_DIR='$PROMPTS_DIR'
pkill -f worker.jar 2>/dev/null; sleep 1
nohup java -jar ~/worker.jar > ~/worker.log 2>&1 &
echo "Worker iniciado (PID: \$!)"
"@

Write-Host ""
Write-Host "==> Iniciando Worker 1 ($WORKER1)..."
ssh -i $KEY -o StrictHostKeyChecking=no $WORKER1 $REMOTE_CMD

Write-Host ""
Write-Host "==> Iniciando Worker 2 ($WORKER2)..."
ssh -i $KEY -o StrictHostKeyChecking=no $WORKER2 $REMOTE_CMD

Write-Host ""
Write-Host "Pronto! Acompanhe os logs com:"
Write-Host "  ssh -i `"$KEY`" $WORKER1 'tail -f ~/worker.log'"
Write-Host "  ssh -i `"$KEY`" $WORKER2 'tail -f ~/worker.log'"
