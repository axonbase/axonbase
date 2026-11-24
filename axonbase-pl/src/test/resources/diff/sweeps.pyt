#=== sw_fmt_int_0
def main():
    return [format(v, '') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_1
def main():
    return [format(v, 'd') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_2
def main():
    return [format(v, '5d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_3
def main():
    return [format(v, '<5d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_4
def main():
    return [format(v, '^7d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_5
def main():
    return [format(v, '05d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_6
def main():
    return [format(v, '+d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_7
def main():
    return [format(v, ' d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_8
def main():
    return [format(v, ',d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_9
def main():
    return [format(v, '_d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_10
def main():
    return [format(v, 'x') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_11
def main():
    return [format(v, 'X') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_12
def main():
    return [format(v, '#x') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_13
def main():
    return [format(v, 'o') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_14
def main():
    return [format(v, 'b') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_15
def main():
    return [format(v, '08b') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_16
def main():
    return [format(v, '10') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_17
def main():
    return [format(v, '>10,d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_18
def main():
    return [format(v, '*^9d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_19
def main():
    return [format(v, '+08d') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_20
def main():
    return [format(v, '#o') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_int_21
def main():
    return [format(v, '#b') for v in [0, 7, -7, 42, -1234567, 255, 100000]]

#=== sw_fmt_float_0
def main():
    return [format(v, '') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_1
def main():
    return [format(v, '.2f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_2
def main():
    return [format(v, '10.3f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_3
def main():
    return [format(v, '<10.1f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_4
def main():
    return [format(v, '^10.2f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_5
def main():
    return [format(v, '010.2f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_6
def main():
    return [format(v, '+.1f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_7
def main():
    return [format(v, 'e') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_8
def main():
    return [format(v, '.3e') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_9
def main():
    return [format(v, 'E') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_10
def main():
    return [format(v, 'g') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_11
def main():
    return [format(v, '.3g') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_12
def main():
    return [format(v, '.0f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_13
def main():
    return [format(v, '%') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_14
def main():
    return [format(v, '.1%') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_15
def main():
    return [format(v, ',.2f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_16
def main():
    return [format(v, '_.1f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_17
def main():
    return [format(v, '12') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_18
def main():
    return [format(v, '>12') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_19
def main():
    return [format(v, '.4') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_20
def main():
    return [format(v, 'G') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_21
def main():
    return [format(v, '+e') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_22
def main():
    return [format(v, '.0e') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_float_23
def main():
    return [format(v, '#.0f') for v in [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]]

#=== sw_fmt_str_0
def main():
    return [format(v, '') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_1
def main():
    return [format(v, 's') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_2
def main():
    return [format(v, '>8') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_3
def main():
    return [format(v, '<8') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_4
def main():
    return [format(v, '^8') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_5
def main():
    return [format(v, '*>8') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_6
def main():
    return [format(v, '->8') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_7
def main():
    return [format(v, '.1') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_8
def main():
    return [format(v, '.2s') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_9
def main():
    return [format(v, '10.2') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_str_10
def main():
    return [format(v, '_^9') for v in ['', 'a', 'abc', 'olá', 'xxxxxxxxxxxx']]

#=== sw_fmt_int_como_float
def main():
    return [format(v, '.2f') for v in [0, 7, -7, 1000000]] + [format(v, 'e') for v in [1, 10, 12345]]

#=== sw_fmt_bool
def main():
    return [format(True, ''), format(False, 'd'), format(True, '>6'), f'{True}', f'{None}']

#=== sw_fmt_erros
def main():
    r = []
    for v, s in [(1, 's_x'), ('a', 'd'), (1.5, 'x'), (1, '.2d'), ('a', '05')]:
        try:
            r.append(format(v, s))
        except ValueError:
            r.append('erro')
    return r

#=== sw_ar_soma_int
def calc(a, b):
    try:
        return a + b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)]]

#=== sw_ar_soma_float
def calc(a, b):
    try:
        return a + b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7.5, 2.0), (-7.5, 2.5), (0.1, 0.2), (10000000000.0, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-05, 100000.0), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)]]

#=== sw_ar_soma_misto
def calc(a, b):
    try:
        return a + b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2.0), (2.5, 3), (-4, 0.5), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1000.0, 7), (5, -2.0)]]

#=== sw_ar_sub_int
def calc(a, b):
    try:
        return a - b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)]]

#=== sw_ar_sub_float
def calc(a, b):
    try:
        return a - b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7.5, 2.0), (-7.5, 2.5), (0.1, 0.2), (10000000000.0, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-05, 100000.0), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)]]

#=== sw_ar_sub_misto
def calc(a, b):
    try:
        return a - b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2.0), (2.5, 3), (-4, 0.5), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1000.0, 7), (5, -2.0)]]

#=== sw_ar_mul_int
def calc(a, b):
    try:
        return a * b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)]]

#=== sw_ar_mul_float
def calc(a, b):
    try:
        return a * b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7.5, 2.0), (-7.5, 2.5), (0.1, 0.2), (10000000000.0, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-05, 100000.0), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)]]

#=== sw_ar_mul_misto
def calc(a, b):
    try:
        return a * b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2.0), (2.5, 3), (-4, 0.5), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1000.0, 7), (5, -2.0)]]

#=== sw_ar_div_int
def calc(a, b):
    try:
        return a / b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)]]

#=== sw_ar_div_float
def calc(a, b):
    try:
        return a / b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7.5, 2.0), (-7.5, 2.5), (0.1, 0.2), (10000000000.0, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-05, 100000.0), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)]]

#=== sw_ar_div_misto
def calc(a, b):
    try:
        return a / b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2.0), (2.5, 3), (-4, 0.5), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1000.0, 7), (5, -2.0)]]

#=== sw_ar_piso_int
def calc(a, b):
    try:
        return a // b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)]]

#=== sw_ar_piso_float
def calc(a, b):
    try:
        return a // b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7.5, 2.0), (-7.5, 2.5), (0.1, 0.2), (10000000000.0, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-05, 100000.0), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)]]

#=== sw_ar_piso_misto
def calc(a, b):
    try:
        return a // b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2.0), (2.5, 3), (-4, 0.5), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1000.0, 7), (5, -2.0)]]

#=== sw_ar_mod_int
def calc(a, b):
    try:
        return a % b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)]]

#=== sw_ar_mod_float
def calc(a, b):
    try:
        return a % b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7.5, 2.0), (-7.5, 2.5), (0.1, 0.2), (10000000000.0, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-05, 100000.0), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)]]

#=== sw_ar_mod_misto
def calc(a, b):
    try:
        return a % b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2.0), (2.5, 3), (-4, 0.5), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1000.0, 7), (5, -2.0)]]

#=== sw_ar_pot_int
def calc(a, b):
    try:
        return a ** b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)]]

#=== sw_ar_pot_float
def calc(a, b):
    try:
        return a ** b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7.5, 2.0), (0.1, 0.2), (10000000000.0, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-05, 100000.0), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)]]

#=== sw_ar_pot_misto
def calc(a, b):
    try:
        return a ** b
    except ZeroDivisionError:
        return 'zero'
    except ValueError:
        return 'valor'
    except OverflowError:
        return 'overflow'

def main():
    return [calc(a, b) for a, b in [(7, 2.0), (2.5, 3), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1000.0, 7), (5, -2.0)]]

#=== sw_cmp_0
def main():
    return [a < b for a, b in [(1, 2), (2, 2), (3, 2), (1, 1.5), (2, 2.0), (-1, -1.0), (0.30000000000000004, 0.3), (True, 1), (1000000000000000, 1000000000000000.0), (9007199254740992, 9007199254740992.0)]]

#=== sw_cmp_1
def main():
    return [a <= b for a, b in [(1, 2), (2, 2), (3, 2), (1, 1.5), (2, 2.0), (-1, -1.0), (0.30000000000000004, 0.3), (True, 1), (1000000000000000, 1000000000000000.0), (9007199254740992, 9007199254740992.0)]]

#=== sw_cmp_2
def main():
    return [a > b for a, b in [(1, 2), (2, 2), (3, 2), (1, 1.5), (2, 2.0), (-1, -1.0), (0.30000000000000004, 0.3), (True, 1), (1000000000000000, 1000000000000000.0), (9007199254740992, 9007199254740992.0)]]

#=== sw_cmp_3
def main():
    return [a >= b for a, b in [(1, 2), (2, 2), (3, 2), (1, 1.5), (2, 2.0), (-1, -1.0), (0.30000000000000004, 0.3), (True, 1), (1000000000000000, 1000000000000000.0), (9007199254740992, 9007199254740992.0)]]

#=== sw_cmp_4
def main():
    return [a == b for a, b in [(1, 2), (2, 2), (3, 2), (1, 1.5), (2, 2.0), (-1, -1.0), (0.30000000000000004, 0.3), (True, 1), (1000000000000000, 1000000000000000.0), (9007199254740992, 9007199254740992.0)]]

#=== sw_cmp_5
def main():
    return [a != b for a, b in [(1, 2), (2, 2), (3, 2), (1, 1.5), (2, 2.0), (-1, -1.0), (0.30000000000000004, 0.3), (True, 1), (1000000000000000, 1000000000000000.0), (9007199254740992, 9007199254740992.0)]]

#=== sw_fatia_lista_None
def main():
    xs = list(range(10))
    return [xs[:], xs[2:], xs[:5], xs[1:7], xs[-3:], xs[:-2], xs[-7:-2], xs[8:2], xs[20:], xs[:-20], xs[5:5], xs[-1:]]

#=== sw_fatia_str_None
def main():
    s = 'abcdefghij'
    return [s[:], s[2:], s[:5], s[1:7], s[-3:], s[:-2], s[-7:-2], s[8:2], s[20:], s[:-20], s[5:5], s[-1:]]

#=== sw_fatia_tupla_None
def main():
    t = tuple(range(10))
    s = t
    return [s[:], s[2:], s[:5], s[1:7], s[-3:], s[:-2], s[-7:-2], s[8:2], s[20:], s[:-20], s[5:5], s[-1:]]

#=== sw_fatia_lista_1
def main():
    xs = list(range(10))
    return [xs[::1], xs[2::1], xs[:5:1], xs[1:7:1], xs[-3::1], xs[:-2:1], xs[-7:-2:1], xs[8:2:1], xs[20::1], xs[:-20:1], xs[5:5:1], xs[-1::1]]

#=== sw_fatia_str_1
def main():
    s = 'abcdefghij'
    return [s[::1], s[2::1], s[:5:1], s[1:7:1], s[-3::1], s[:-2:1], s[-7:-2:1], s[8:2:1], s[20::1], s[:-20:1], s[5:5:1], s[-1::1]]

#=== sw_fatia_tupla_1
def main():
    t = tuple(range(10))
    s = t
    return [s[::1], s[2::1], s[:5:1], s[1:7:1], s[-3::1], s[:-2:1], s[-7:-2:1], s[8:2:1], s[20::1], s[:-20:1], s[5:5:1], s[-1::1]]

#=== sw_fatia_lista_2
def main():
    xs = list(range(10))
    return [xs[::2], xs[2::2], xs[:5:2], xs[1:7:2], xs[-3::2], xs[:-2:2], xs[-7:-2:2], xs[8:2:2], xs[20::2], xs[:-20:2], xs[5:5:2], xs[-1::2]]

#=== sw_fatia_str_2
def main():
    s = 'abcdefghij'
    return [s[::2], s[2::2], s[:5:2], s[1:7:2], s[-3::2], s[:-2:2], s[-7:-2:2], s[8:2:2], s[20::2], s[:-20:2], s[5:5:2], s[-1::2]]

#=== sw_fatia_tupla_2
def main():
    t = tuple(range(10))
    s = t
    return [s[::2], s[2::2], s[:5:2], s[1:7:2], s[-3::2], s[:-2:2], s[-7:-2:2], s[8:2:2], s[20::2], s[:-20:2], s[5:5:2], s[-1::2]]

#=== sw_fatia_lista_3
def main():
    xs = list(range(10))
    return [xs[::3], xs[2::3], xs[:5:3], xs[1:7:3], xs[-3::3], xs[:-2:3], xs[-7:-2:3], xs[8:2:3], xs[20::3], xs[:-20:3], xs[5:5:3], xs[-1::3]]

#=== sw_fatia_str_3
def main():
    s = 'abcdefghij'
    return [s[::3], s[2::3], s[:5:3], s[1:7:3], s[-3::3], s[:-2:3], s[-7:-2:3], s[8:2:3], s[20::3], s[:-20:3], s[5:5:3], s[-1::3]]

#=== sw_fatia_tupla_3
def main():
    t = tuple(range(10))
    s = t
    return [s[::3], s[2::3], s[:5:3], s[1:7:3], s[-3::3], s[:-2:3], s[-7:-2:3], s[8:2:3], s[20::3], s[:-20:3], s[5:5:3], s[-1::3]]

#=== sw_fatia_lista_m1
def main():
    xs = list(range(10))
    return [xs[::-1], xs[2::-1], xs[:5:-1], xs[1:7:-1], xs[-3::-1], xs[:-2:-1], xs[-7:-2:-1], xs[8:2:-1], xs[20::-1], xs[:-20:-1], xs[5:5:-1], xs[-1::-1]]

#=== sw_fatia_str_m1
def main():
    s = 'abcdefghij'
    return [s[::-1], s[2::-1], s[:5:-1], s[1:7:-1], s[-3::-1], s[:-2:-1], s[-7:-2:-1], s[8:2:-1], s[20::-1], s[:-20:-1], s[5:5:-1], s[-1::-1]]

#=== sw_fatia_tupla_m1
def main():
    t = tuple(range(10))
    s = t
    return [s[::-1], s[2::-1], s[:5:-1], s[1:7:-1], s[-3::-1], s[:-2:-1], s[-7:-2:-1], s[8:2:-1], s[20::-1], s[:-20:-1], s[5:5:-1], s[-1::-1]]

#=== sw_fatia_lista_m2
def main():
    xs = list(range(10))
    return [xs[::-2], xs[2::-2], xs[:5:-2], xs[1:7:-2], xs[-3::-2], xs[:-2:-2], xs[-7:-2:-2], xs[8:2:-2], xs[20::-2], xs[:-20:-2], xs[5:5:-2], xs[-1::-2]]

#=== sw_fatia_str_m2
def main():
    s = 'abcdefghij'
    return [s[::-2], s[2::-2], s[:5:-2], s[1:7:-2], s[-3::-2], s[:-2:-2], s[-7:-2:-2], s[8:2:-2], s[20::-2], s[:-20:-2], s[5:5:-2], s[-1::-2]]

#=== sw_fatia_tupla_m2
def main():
    t = tuple(range(10))
    s = t
    return [s[::-2], s[2::-2], s[:5:-2], s[1:7:-2], s[-3::-2], s[:-2:-2], s[-7:-2:-2], s[8:2:-2], s[20::-2], s[:-20:-2], s[5:5:-2], s[-1::-2]]

#=== sw_fatia_lista_m3
def main():
    xs = list(range(10))
    return [xs[::-3], xs[2::-3], xs[:5:-3], xs[1:7:-3], xs[-3::-3], xs[:-2:-3], xs[-7:-2:-3], xs[8:2:-3], xs[20::-3], xs[:-20:-3], xs[5:5:-3], xs[-1::-3]]

#=== sw_fatia_str_m3
def main():
    s = 'abcdefghij'
    return [s[::-3], s[2::-3], s[:5:-3], s[1:7:-3], s[-3::-3], s[:-2:-3], s[-7:-2:-3], s[8:2:-3], s[20::-3], s[:-20:-3], s[5:5:-3], s[-1::-3]]

#=== sw_fatia_tupla_m3
def main():
    t = tuple(range(10))
    s = t
    return [s[::-3], s[2::-3], s[:5:-3], s[1:7:-3], s[-3::-3], s[:-2:-3], s[-7:-2:-3], s[8:2:-3], s[20::-3], s[:-20:-3], s[5:5:-3], s[-1::-3]]

#=== sw_str_0
def main():
    return [s.upper() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_1
def main():
    return [s.lower() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_2
def main():
    return [s.title() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_3
def main():
    return [s.capitalize() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_4
def main():
    return [s.swapcase() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_5
def main():
    return [s.strip() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_6
def main():
    return [s.lstrip() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_7
def main():
    return [s.rstrip() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_8
def main():
    return [s.split() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_9
def main():
    return [s.split('o') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_10
def main():
    return [s.split(' ', 1) for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_11
def main():
    return [s.rsplit(' ', 1) for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_12
def main():
    return [s.isdigit() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_13
def main():
    return [s.isalpha() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_14
def main():
    return [s.isalnum() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_15
def main():
    return [s.isspace() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_16
def main():
    return [s.isupper() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_17
def main():
    return [s.islower() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_18
def main():
    return [s.zfill(8) for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_19
def main():
    return [s.center(15, '*') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_20
def main():
    return [s.ljust(14, '.') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_21
def main():
    return [s.rjust(14) for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_22
def main():
    return [s.find('o') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_23
def main():
    return [s.rfind('o') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_24
def main():
    return [s.count('a') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_25
def main():
    return [s.count('') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_26
def main():
    return [s.startswith('H') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_27
def main():
    return [s.endswith('d') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_28
def main():
    return [s.replace('o', '0') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_29
def main():
    return [s.replace('', '-') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_30
def main():
    return [s.partition(' ') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_31
def main():
    return [s.rpartition(' ') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_32
def main():
    return [s.splitlines() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_33
def main():
    return [s.title().swapcase() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_34
def main():
    return [s.strip('a').upper() for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_35
def main():
    return [s.removeprefix('a') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_36
def main():
    return [s.removesuffix('d') for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_str_37
def main():
    return [s.index('a') if 'a' in s else -1 for s in ['', '  a b  ', 'Hello World', 'ola, mundo', 'ÁÉÍ óú', '123', 'a1b2', 'UPPER lower', 'x', '   ', 'tab\there', 'abababab', 'João da Silva']]

#=== sw_float_repr_1
def main():
    return [0.1, 0.2, 0.3, 0.3333333333333333, 0.6666666666666666, 3.3333333333333335, 1000000000000000.0, 1e+16, 1e+17, 123456789012345.6, 1234567890123456.8, 0.0001, 1e-05, 0.00012, 1.5e-07]

#=== sw_float_repr_2
def main():
    return [1e+100, 1.2345678901234567e-200, 5e-324, 1.7976931348623157e+308, 4.35, 4.349999999999999, 100.0, 100.0, 12.0, 0.5, 1.4142135623730951, 3.14159265358979, 9007199254740992.0, 12345600.0, 0.7999999999999999]

#=== sw_float_str_1
def main():
    return [str(x) for x in [0.1, 0.2, 0.3, 0.3333333333333333, 0.6666666666666666, 3.3333333333333335, 1000000000000000.0, 1e+16, 1e+17, 123456789012345.6, 1234567890123456.8, 0.0001, 1e-05, 0.00012, 1.5e-07]]

#=== sw_float_str_2
def main():
    return [str(x) + '|' + repr(x) for x in [1e+100, 1.2345678901234567e-200, 5e-324, 1.7976931348623157e+308, 4.35, 4.349999999999999, 100.0, 100.0, 12.0, 0.5, 1.4142135623730951, 3.14159265358979, 9007199254740992.0, 12345600.0, 0.7999999999999999]]

#=== sw_round_n
def main():
    return [round(x) for x in [0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5, 0.49999, 2.675, 1.005, 1234.5678, 0.125, 0.375, 1000000000000000.5, 99.995, 7.0]]

#=== sw_round_0
def main():
    return [round(x, 0) for x in [0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5, 0.49999, 2.675, 1.005, 1234.5678, 0.125, 0.375, 1000000000000000.5, 99.995, 7.0]]

#=== sw_round_1
def main():
    return [round(x, 1) for x in [0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5, 0.49999, 2.675, 1.005, 1234.5678, 0.125, 0.375, 1000000000000000.5, 99.995, 7.0]]

#=== sw_round_2
def main():
    return [round(x, 2) for x in [0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5, 0.49999, 2.675, 1.005, 1234.5678, 0.125, 0.375, 1000000000000000.5, 99.995, 7.0]]

#=== sw_round_3
def main():
    return [round(x, 3) for x in [0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5, 0.49999, 2.675, 1.005, 1234.5678, 0.125, 0.375, 1000000000000000.5, 99.995, 7.0]]

#=== sw_round_m1
def main():
    return [round(x, -1) for x in [0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5, 0.49999, 2.675, 1.005, 1234.5678, 0.125, 0.375, 1000000000000000.5, 99.995, 7.0]]

#=== sw_int_de_float
def main():
    return [int(x) for x in [0.0, 0.9, -0.9, 1e10, -1e10, 123.999, 2.5e15]]

#=== sw_int_de_texto
def main():
    r = []
    for t in ['1', ' 2 ', '+3', '-4', '1_0', '0', '007', 'x', '1.5', '', '1 2']:
        try:
            r.append(int(t))
        except ValueError:
            r.append('erro')
    return r

#=== sw_float_de_texto
def main():
    r = []
    for t in ['1', '2.5', ' 3.5 ', '-1e3', '.5', '5.', 'inf', '-inf', 'x', '', '1e', '1_0.5', '1__0']:
        try:
            r.append(float(t))
        except ValueError:
            r.append('erro')
    return r

#=== sw_sort_tipos
def main():
    return [sorted([3, 1.5, 2, True, 0]), sorted(['b', 'B', 'a', 'A', '1', '_']), sorted([(1, 'b'), (1, 'a'), (0, 'z')]), sorted([[2, 1], [1, 5], [1, 2]]), sorted([-1, -10, 5, 0], key=abs), sorted(['aa', 'b', 'ccc'], key=len, reverse=True)]

#=== sw_minmax_variados
def main():
    return [min([3, 1.5, 2]), max([3, 1.5, 2]), min('hello'), max(['a', 'ab', 'b']), min([[2], [1, 9]]), max((1, 2), (1, 3)), min(1, 2.0), max(1, 1.0), max(1.0, 1)]

#=== sw_sum_variados
def main():
    return [sum([]), sum([1, 2, 3]), sum([0.1] * 3), sum([1, 2.5]), sum([True, True]), sum(range(1000)), sum([0.1, 0.2, 0.3, 0.4]), sum([1e100, 1.0, -1e100]), sum([[1], [2, 3]], []), sum((x for x in range(5)), 100)]

#=== sw_divmod_pares
def main():
    return [divmod(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 3), (10, 5), (7.5, 2), (-7.5, 2), (1e10, 3)]]

#=== sw_pow_variados
def main():
    return [pow(2, 8), pow(2, 0), pow(0, 0), pow(-2, 3), pow(2, -3), pow(2.5, 2), pow(9, 0.5), pow(10, 18), pow(7, 2, 5), pow(2, 100, 1000007)]

#=== sw_bool_truthiness
def main():
    vals = [0, 1, -1, 0.0, 0.1, '', ' ', [], [0], (), (0,), {}, {0: 0}, set(), {0}, None, True, False]
    return [bool(v) for v in vals]

#=== sw_dict_get_default
def main():
    d = {'a': 1, 'b': None, 'c': 0, 'd': ''}
    return [d.get(k, 'padrao') for k in 'abcdx'] + [d[k] or 'vazio' for k in 'abcd']

