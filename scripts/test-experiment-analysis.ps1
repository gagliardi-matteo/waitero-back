param(
    [string]$BaseUrl = "http://localhost:8080",
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][long]$RistoranteId
)

$ErrorActionPreference = "Stop"

$loginPayload = @{
    email = $Email
    password = $Password
} | ConvertTo-Json

$authResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/auth/local-login" `
    -ContentType "application/json" `
    -Body $loginPayload

if (-not $authResponse.accessToken) {
    throw "Authentication failed: accessToken missing from /api/auth/local-login response"
}

$headers = @{
    Authorization = "Bearer $($authResponse.accessToken)"
}

$analysis = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/experiment/analysis?ristoranteId=$RistoranteId" `
    -Headers $headers

if (-not $analysis.metrics) {
    throw "Invalid response: metrics object is missing"
}

foreach ($variant in @("A", "B", "C")) {
    $variantMetrics = $analysis.metrics.$variant
    if (-not $variantMetrics) {
        throw "Invalid response: missing metrics for variant $variant"
    }

    foreach ($field in @("totalRevenue", "totalOrders", "totalSessions", "rps", "aov", "cr")) {
        $value = $variantMetrics.$field
        if ($null -eq $value) {
            throw "Invalid response: metrics.$variant.$field is null"
        }
        if ($value -is [double] -and [double]::IsNaN($value)) {
            throw "Invalid response: metrics.$variant.$field is NaN"
        }
    }
}

if (-not $analysis.winner) {
    throw "Invalid response: winner is missing"
}

if (-not $analysis.currentMode) {
    throw "Invalid response: currentMode is missing"
}

Write-Host "Experiment analysis endpoint OK"
Write-Host "Winner: $($analysis.winner)"
Write-Host "Current mode: $($analysis.currentMode)"
Write-Host "Target mode: $($analysis.targetMode)"
Write-Host "Action: $($analysis.action)"
Write-Host ""
Write-Host "cURL example:"
Write-Host "curl -X GET `"$BaseUrl/api/experiment/analysis?ristoranteId=$RistoranteId`" -H `"Authorization: Bearer <TOKEN>`""
