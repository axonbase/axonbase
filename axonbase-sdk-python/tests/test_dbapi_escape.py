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
            out.append(literal[i + 1])
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
