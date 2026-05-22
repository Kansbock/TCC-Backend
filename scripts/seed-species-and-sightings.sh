#!/usr/bin/env bash
#
# Popula o banco com espécies e um avistamento para cada uma.
#
# Pré-requisitos:
#   - Backend rodando (padrão: http://localhost:8080)
#   - python3 instalado (faz todo o HTTP — evita problemas de curl no Git Bash/Windows)
#   - Usuário já cadastrado no app
#
# IMPORTANTE — senha:
#   Informe LOGIN_PASSWORD em texto plano (a mesma que você digita no app).
#   O script aplica SHA-256 antes de enviar ao /api/auth/login, igual ao app
#   (services/auth.ts). O backend compara esse hash com o BCrypt armazenado.
#   NÃO passe a senha já hasheada manualmente.
#
# Uso:
#   chmod +x scripts/seed-species-and-sightings.sh
#   LOGIN_EMAIL=seu@email.com LOGIN_PASSWORD=suasenha ./scripts/seed-species-and-sightings.sh
#
# Variáveis opcionais:
#   API_BASE_URL   (padrão: http://localhost:8080)
#   LOGIN_EMAIL    (obrigatório — e-mail cadastrado)
#   LOGIN_PASSWORD (obrigatório — senha em texto plano; o script faz SHA-256)
#   DEBUG          (qualquer valor não vazio para logs detalhados do HTTP)

set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
LOGIN_EMAIL="${LOGIN_EMAIL:-}"
LOGIN_PASSWORD="${LOGIN_PASSWORD:-}"
DEBUG="${DEBUG:-}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "✗ python3 é obrigatório para este script." >&2
  exit 1
fi

if [[ -z "$LOGIN_EMAIL" || -z "$LOGIN_PASSWORD" ]]; then
  echo "✗ Defina LOGIN_EMAIL e LOGIN_PASSWORD antes de executar." >&2
  echo "  Use a senha em texto plano — o script aplica SHA-256 automaticamente." >&2
  echo "  Exemplo: LOGIN_EMAIL=user@test.com LOGIN_PASSWORD=123456 \\" >&2
  echo "           ./scripts/seed-species-and-sightings.sh" >&2
  exit 1
fi

IMAGE_URL="${IMAGE_URL:-}"
export API_BASE_URL LOGIN_EMAIL LOGIN_PASSWORD DEBUG IMAGE_URL

python3 - <<'PY'
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta

API_BASE_URL = os.environ["API_BASE_URL"].rstrip("/")
LOGIN_EMAIL = os.environ["LOGIN_EMAIL"]
LOGIN_PASSWORD = os.environ["LOGIN_PASSWORD"]
DEBUG = bool(os.environ.get("DEBUG"))

# Imagem padrão usada quando uma espécie não tem URL própria definida abaixo.
# Pode ser sobrescrita com IMAGE_URL=... ao invocar o script.
DEFAULT_IMAGE_URL = os.environ.get(
    "IMAGE_URL",
    "https://placehold.co/600x400/2D6A4F/FFFFFF.png?text=Ave",
)

# Cada espécie: (nome, nome_científico, descrição, dicas, image_url).
# Para usar a imagem padrão, deixe o campo image_url como "" (string vazia).
# Cole aqui o link direto da foto (precisa terminar em .jpg/.png e funcionar
# fora do navegador — links de busca do Google não funcionam).
SPECIES = [
    (
        "Bem-te-vi",
        "Pitangus sulphuratus",
        "Ave de porte médio com peito amarelo vivo e máscara preta. "
        "Famosa pelo canto que dá origem ao seu nome.",
        "Atraído por frutas como banana e mamão deixadas em comedouros abertos.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTloln2azOV4RtrhSDmqNRitO7yuCCx5FzXnQ&s",  # image_url
    ),
    (
        "Neinei",
        "Megarynchus pitangua",
        "Muito parecido com o bem-te-vi, mas com bico mais largo e canto distinto. "
        "Vive em bordas de mata e jardins arborizados.",
        "Oferece frutas suculentas e mantenha árvores frutíferas próximas ao comedouro.",
        "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6f/NEINEI_%28Megarynchus_pitangua%29.jpg/330px-NEINEI_%28Megarynchus_pitangua%29.jpg",
    ),
    (
        "Cambacica",
        "Coereba flaveola",
        "Pequena ave de barriga amarela e sobrancelha branca. "
        "Alimenta-se de néctar, frutas e pequenos insetos.",
        "Coloque bebedouros com água açucarada (1 parte de açúcar para 4 de água).",
        "https://s2.glbimg.com/ly1d11ZNHYYQLU7o4WS5-sE3hVs=/1200x630/s.glbimg.com/jo/g1/f/original/2016/07/08/cambacica_coereba_flaveola.jpg",
    ),
    (
        "Sabiá-laranjeira",
        "Turdus rufiventris",
        "Ave-símbolo do Brasil, conhecida pelo canto melodioso e pelo peito laranja.",
        "Adora frutas maduras como banana, mamão e laranja cortada ao meio.",
        "https://www.ra-bugio.org.br/especies/115.jpg",
    ),
    (
        "Sabiá-barranco",
        "Turdus leucomelas",
        "Sabiá de cabeça acinzentada e barriga clara. Canto suave e fluído.",
        "Mantém-se em áreas sombreadas; ofereça frutas em locais protegidos do sol.",
        "https://upload.wikimedia.org/wikipedia/commons/b/b1/Turdus_leucomelas.jpg?utm_source=pt.wikipedia.org&utm_campaign=index&utm_content=original",
    ),
    (
        "Sanhaço-cinzento",
        "Tangara sayaca",
        "Sanhaço de plumagem azul-acinzentada, comum em quintais e praças urbanas.",
        "Vive em bando — disponibilize várias frutas espalhadas pelo comedouro.",
        "https://www.ra-bugio.org.br/especies/762.jpg",
    ),
    (
        "Sanhaço-dos-coqueiros",
        "Tangara palmarum",
        "Sanhaço esverdeado, frequentemente visto em palmeiras e coqueiros.",
        "Plante palmeiras nativas e ofereça frutas como banana e mamão.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRXSrD5FU2-aksdtPIZBb0W3hU_oOagPIJkYA&s",
    ),
    (
        "Rolinha-roxa",
        "Columbina talpacoti",
        "Pequena rolinha de tom avermelhado, comum em áreas urbanas e campos.",
        "Espalhe sementes pequenas (painço, alpiste) no chão ou em comedouros baixos.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRagpR-Jnf7fFAGF_77tMBmI_CIYSIXaYLM9g&s",
    ),
    (
        "Pombo-Doméstico",
        "Columba livia",
        "Pombo cosmopolita, presente em praticamente todas as cidades do mundo.",
        "Evite alimentar para não estimular superpopulação em áreas urbanas.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTPz4_-oFXxFqf4CDSmEuLMXwnydNrBZXZgiw&s",
    ),
    (
        "Avoante",
        "Zenaida auriculata",
        "Pomba migratória de pescoço com manchas iridescentes. "
        "Forma grandes bandos durante a migração.",
        "Disponibilize milho e sementes em áreas amplas e abertas.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQLtzrPyIXRgxtjBUwCzCdozsXns4wZ1bWZDQ&s",
    ),
    (
        "Pardal",
        "Passer domesticus",
        "Pequena ave introduzida, adaptada à vida urbana. "
        "Macho com peito preto característico.",
        "Sementes variadas (alpiste, painço, girassol) atraem com facilidade.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcS9zqShd6cyk8osg7h4JciP7px8i61bUPaUTg&s",
    ),
    (
        "Tico-tico",
        "Zonotrichia capensis",
        "Ave de canto inconfundível, com topete e listras escuras na cabeça.",
        "Coloque sementes pequenas no chão; gosta de ciscar em áreas com vegetação rasteira.",
        "https://cultura.jundiai.sp.gov.br/wp-content/uploads/2019/10/Tico-tico.jpg",
    ),
    (
        "Corruíra",
        "Troglodytes musculus",
        "Pequena ave marrom muito ativa, com cauda em pé. "
        "Conhecida pelo canto borbulhante.",
        "Mantenha pequenas tocas ou caixas-ninho com entrada estreita em áreas sombreadas.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT96m4TQGVoQLhSLDXRgjOjEqJt-vD1-FaGcQ&s",
    ),
    (
        "Canário-da-terra",
        "Sicalis flaveola",
        "Ave amarela brilhante, muito apreciada pelo canto.",
        "Ofereça alpiste e painço; aprecia banhos em bebedouros rasos.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS_iisnHiVQbt-5HZ8jdPIAjRhE-QGg9vhFA&s",
    ),
    (
        "Periquito-rico",
        "Brotogeris tirica",
        "Periquito verde-claro, sociável e barulhento. "
        "Forma bandos médios em áreas arborizadas.",
        "Plante árvores frutíferas nativas; aceita milho verde e frutas maduras.",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSl1RHR53FtmfSYSxWmThqhqyya83Y-tYwh5g&s",
    ),
    (
        "Periquitão",
        "Psittacara leucophthalmus",
        "Periquito maior, com anel branco ao redor do olho. "
        "Voa em bandos ruidosos.",
        "Oferece frutas grandes (manga, mamão) e milho em comedouros amplos.",
        "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7b/Periquit%C3%A3o_maracan%C3%A3.jpg/250px-Periquit%C3%A3o_maracan%C3%A3.jpg",
    ),
]


def color(code, msg):
    return f"\033[{code}m{msg}\033[0m"


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def ok(msg):
    log(color("32", "✓") + " " + msg)


def warn(msg):
    log(color("33", "!") + " " + msg)


def err(msg):
    log(color("31", "✗") + " " + msg)


def debug(msg):
    if DEBUG:
        log(color("36", "›") + " " + msg)


def http_request(method, path, body=None, token=None):
    url = API_BASE_URL + path
    headers = {"Accept": "application/json"}
    data = None

    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
        headers["Content-Length"] = str(len(data))

    if token:
        headers["Authorization"] = f"Bearer {token}"

    debug(f"{method} {url}")
    debug(f"  headers: {dict(headers)}")
    if data is not None:
        preview = data.decode("utf-8", errors="replace")
        if len(preview) > 400:
            preview = preview[:400] + "..."
        debug(f"  body: {preview}")

    req = urllib.request.Request(url, data=data, method=method, headers=headers)

    try:
        with urllib.request.urlopen(req) as resp:
            status = resp.getcode()
            raw = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        status = e.code
        raw = e.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as e:
        err(f"Falha de conexão em {url}: {e.reason}")
        sys.exit(1)

    debug(f"  → status={status}")
    if raw:
        preview = raw if len(raw) <= 400 else raw[:400] + "..."
        debug(f"  ← body: {preview}")

    parsed = None
    if raw:
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = raw

    return status, parsed, raw


def auth_error_hint():
    err("Falha de autenticação (HTTP 403).")
    err("Verifique LOGIN_EMAIL e LOGIN_PASSWORD (senha em texto plano).")
    err("O script aplica SHA-256 automaticamente — não envie a senha já hasheada.")
    err("Confirme se o backend está rodando em " + API_BASE_URL + ".")
    err("Após alterar SecurityConfig.java, reinicie o backend.")
    err("Rode novamente com DEBUG=1 para ver os headers enviados/recebidos.")


def login():
    log("Conectando em " + API_BASE_URL + "...")
    log("Aplicando SHA-256 na senha antes do login (igual ao app)...")

    password_sha256 = hashlib.sha256(LOGIN_PASSWORD.encode("utf-8")).hexdigest()
    payload = {"email": LOGIN_EMAIL, "password": password_sha256}

    status, body, raw = http_request("POST", "/api/auth/login", body=payload)
    if status != 200:
        err(f"Falha no login (HTTP {status}): {raw}")
        err("Confirme e-mail/senha — o script aplica SHA-256 automaticamente.")
        sys.exit(1)

    token = (body or {}).get("token") if isinstance(body, dict) else None
    if not token:
        err("Token JWT não retornado pelo login.")
        sys.exit(1)

    ok("Login realizado com sucesso.")
    return token


def verify_auth(token):
    status, _, raw = http_request("GET", "/api/sightings", token=token)
    if status != 200:
        auth_error_hint()
        err(f"GET /api/sightings retornou {status}: {raw}")
        sys.exit(1)
    ok("Token JWT validado em GET /api/sightings.")

    # Também testa POST autenticado com body inválido. Se o JWT estiver OK,
    # devemos receber 400 (validação) — não 403.
    status, _, raw = http_request(
        "POST", "/api/sightings", body={}, token=token
    )
    if status == 403:
        auth_error_hint()
        err(
            "POST /api/sightings autenticado retornou 403. "
            "O backend está rejeitando o token em POSTs."
        )
        err("Resposta: " + (raw or "<vazio>"))
        sys.exit(1)
    if status not in (400, 422):
        warn(
            f"POST /api/sightings (body vazio) retornou {status} (esperado 400). "
            "Continuando mesmo assim."
        )
    ok("Token JWT também aceito em POST.")


def find_species_id(name, token):
    from urllib.parse import quote

    status, body, raw = http_request(
        "GET", f"/api/species?name={quote(name)}", token=token
    )
    if status != 200:
        return None
    if isinstance(body, list) and body:
        for item in body:
            if isinstance(item, dict) and item.get("name") == name:
                return item.get("id")
        first = body[0]
        if isinstance(first, dict):
            return first.get("id")
    return None


def upsert_species(name, scientific_name, description, tips, image_url, token):
    """Cria a espécie se não existir; caso exista, atualiza descrição,
    dicas e imagem para refletir o seed mais recente."""

    payload = {
        "name": name,
        "scientificName": scientific_name,
        "description": description,
        "tips": tips,
        "imageUrl": image_url or DEFAULT_IMAGE_URL,
    }

    existing = find_species_id(name, token)
    if existing:
        status, body, raw = http_request(
            "PUT", f"/api/species/{existing}", body=payload, token=token
        )
        if status != 200:
            if status == 403:
                auth_error_hint()
            err(
                f"Falha ao atualizar espécie '{name}' "
                f"(HTTP {status}): {raw}"
            )
            sys.exit(1)
        ok(f"Espécie atualizada: {name} (id={existing})")
        return existing

    status, body, raw = http_request(
        "POST", "/api/species", body=payload, token=token
    )
    if status != 201:
        if status == 403:
            auth_error_hint()
        err(f"Falha ao criar espécie '{name}' (HTTP {status}): {raw}")
        sys.exit(1)

    species_id = (body or {}).get("id") if isinstance(body, dict) else None
    ok(f"Espécie criada: {name} (id={species_id})")
    return species_id


def create_sighting(species_id, species_name, day, time_str, image_url, token):
    payload = {
        "species": [
            {"speciesId": species_id, "quantity": 1, "gender": "UNKNOWN"}
        ],
        "date": day,
        "time": time_str,
        "imageUrl": image_url or DEFAULT_IMAGE_URL,
    }

    status, body, raw = http_request(
        "POST", "/api/sightings", body=payload, token=token
    )
    if status != 201:
        if status == 403:
            auth_error_hint()
        err(
            f"Falha ao criar avistamento de '{species_name}' "
            f"(HTTP {status}): {raw}"
        )
        sys.exit(1)

    sighting_id = (body or {}).get("id") if isinstance(body, dict) else None
    ok(
        f"Avistamento criado: {species_name} "
        f"(id={sighting_id}, {day} {time_str})"
    )


def main():
    token = login()
    verify_auth(token)

    today = date.today()
    for index, entry in enumerate(SPECIES):
        name, scientific, description, tips, image_url = entry
        species_id = upsert_species(
            name, scientific, description, tips, image_url, token
        )

        day = (today - timedelta(days=index)).isoformat()
        hour = 8 + (index % 10)
        minute = (index * 3) % 60
        time_str = f"{hour:02d}:{minute:02d}:00"

        create_sighting(species_id, name, day, time_str, image_url, token)

    ok(f"Seed concluído: {len(SPECIES)} espécies e {len(SPECIES)} avistamentos.")


main()
PY
