@catalogue @regression
Feature: Everything the parser has to survive

  The AST subset this repo depends on, in one file — integrations.md X1. Every
  construct here is one the walker claims to handle, so a library upgrade that
  changes any of them fails against this file rather than against a customer's.

  Background:
    Given the seed catalogue is loaded
    And a signed-in test engineer

  @plain
  Scenario: Plain steps
    Given a case exists
    When the tester opens it
    Then the checklist is listed

  @blocks
  Scenario: Block arguments
    the objective, which runs to two lines
    and this is its second one

    Given a payload
      """
      {"id": 7,
       "name": "a doc string keeps its own indentation"}
      """
    When these rows arrive
      | name       | value |
      | a pipe     | a\|b  |
      | a backlash | a\\b  |
      | a newline  | a\nb  |
    Then it is stored

  @comments
  Scenario: The two fields Gherkin has no syntax for
    Given a sign-in form
    # data: alice / hunter2
    When the credentials are submitted
    # expected: the dashboard opens within two seconds
    Then the session starts

  @star
  Scenario: Format-neutral steps
    * one thing happens
    * another thing happens

  @every-keyword
  Scenario: All five keywords, And and But included
    Given a starting balance
    And a second premise
    When a payment is taken
    But nothing else changes
    Then the balance falls

  @outline
  Scenario Outline: Refused, and the refusal names this line
    Given a balance of <amount>
    Then it is <verdict>

    Examples:
      | amount | verdict |
      | 10     | fine    |
      | -1     | refused |
