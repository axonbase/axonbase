import pytest

from axonbase.dbapi import Cursor, ProgrammingError


def decode_literal(literal):
    """Lê o literal como o analisador do AxonQL e exige que ele termine só no fim."""
    assert literal.startswith("'")
    out = []
    i = 1
    while i < len(literal):
        c = literal[i]
        if c == "\\":
            out.append({"n": "\n", "r": "\r"}.get(literal[i + 1], literal[i + 1]))
            i += 2
        elif c == "'":
            assert i == len(literal) - 1, "o valor fechou o literal antes do fim: " + literal
            return "".join(out)
        else:
            out.append(c)
            i += 1
    raise AssertionError("literal sem fim: " + literal)


@pytest.mark.parametrize("value", ["O'Brien", "C:\\novo\\", "x\\'; DELETE alvo; --"])
def test_text_parameter_is_a_single_literal(value):
    assert decode_literal(Cursor._escape(value)) == value


def test_bytes_parameter_is_refused():
    with pytest.raises(ProgrammingError):
        Cursor._escape(b"abc")


def sent_sql(operation, parameters):
    """O texto que o cursor enviaria ao servidor, sem abrir conexão."""
    sent = []

    class Axon:
        def query(self, sql):
            sent.append(sql)
            return sql

    class Bridge:
        def call(self, _):
            return []

    class Connection:
        _sql_translate = False
        _bridge = Bridge()
        axon = Axon()

    Cursor(Connection()).execute(operation, parameters)
    return sent[0]


@pytest.mark.parametrize("number", [-1, -1.5])
def test_negative_number_after_minus_does_not_start_a_comment(number):
    sql = sent_sql(
        "SELECT * FROM conta WHERE a = -%(a)s AND b = %(b)s",
        {"a": number, "b": "\n0; DELETE alvo; --"},
    )

    assert "--" + str(abs(number)) not in sql
    assert "- " + str(number) in sql


def test_line_break_is_written_as_an_escape():
    literal = Cursor._escape("a\r\nb")

    assert "\n" not in literal and "\r" not in literal
    assert decode_literal(literal) == "a\r\nb"
