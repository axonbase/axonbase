"""Safe writing of data inside an AxonQL statement, for the places where a parameter cannot be used."""

import re

_SIMPLE_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def quote_literal(text: str) -> str:
    """Literal de texto do AxonQL, em que a barra invertida é o escape (dobrar a aspa não vale)."""
    escaped = text.replace("\\", "\\\\").replace("'", "\\'")
    # a quebra de linha escrita como \n impede que o valor saia de um comentário de linha do comando
    escaped = escaped.replace("\n", "\\n").replace("\r", "\\r")
    return f"'{escaped}'"


def saga_name(name: str) -> str:
    """Nome de SAGA: identificador simples segue como está; qualquer outro vai como literal de texto."""
    return name if _SIMPLE_NAME.fullmatch(name) else quote_literal(name)
