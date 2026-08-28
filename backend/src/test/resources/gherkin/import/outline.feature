Feature: Discounts

  Scenario: A flat discount applies
    Given a basket worth 100
    When I apply DISCOUNT10
    Then the total is 90

  Scenario Outline: Tiered discounts
    Given a basket worth <total>
    When I apply <code>
    Then the total is <after>

    Examples:
      | total | code       | after |
      | 100   | DISCOUNT10 | 90    |
      | 200   | DISCOUNT20 | 160   |
