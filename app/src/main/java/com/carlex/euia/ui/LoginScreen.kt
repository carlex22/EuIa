// File: euia/ui/LoginScreen.kt
package com.carlex.euia.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts // Import necessário
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.carlex.euia.AppDestinations // Importação crucial para as rotas
import com.carlex.euia.R // Importação R para recursos de string
import com.carlex.euia.viewmodel.AuthViewModel // Importação do AuthViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
    authViewModel: AuthViewModel = viewModel() // ViewModel injetado ou fornecido por padrão
) {
    val emailState = remember { mutableStateOf("") }
    val passwordState = remember { mutableStateOf("") }
    val emailError = remember { mutableStateOf<String?>(null) }
    val passwordError = remember { mutableStateOf<String?>(null) }

    val isLoading by authViewModel.isLoading.collectAsState()
    val authError by authViewModel.error.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState() // Observa o usuário autenticado

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current // Usado para exibir Toasts ou strings de recursos

    // Launcher para o fluxo de login do Google
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Chama o método do ViewModel para lidar com o resultado do login do Google
        authViewModel.handleGoogleSignInResult(result.data)
    }

    // Observa o resultado do login (currentUser) e navega se houver sucesso
    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            // Navega para a tela principal após o login bem-sucedido
            navController.navigate(AppDestinations.PROJECT_MANAGEMENT_ROUTE) {
                // Remove todas as telas anteriores da pilha, exceto a tela inicial do NavGraph
                popUpTo(navController.graph.id) {
                    inclusive = true // Inclui a própria tela inicial na remoção, se desejar um reset total
                }
            }
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.login_success_message),
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Observa erros do ViewModel e os exibe no Snackbar
    LaunchedEffect(authError) {
        authError?.let { message ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Long // Mensagens de erro podem ser mais longas
                )
            }
            // Limpa o erro no ViewModel (se ele tiver um método clearError(), caso contrário, o StateFlow se encarrega)
            // authViewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f) // Ocupa 90% da largura da tela
                    .verticalScroll(rememberScrollState()) // Permite rolagem se o teclado aparecer
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Logo do aplicativo
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground), // Verifique se este drawable existe
                    contentDescription = stringResource(R.string.content_desc_app_logo),
                    modifier = Modifier.size(120.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(32.dp))

                // Campo de E-mail
                OutlinedTextField(
                    value = emailState.value,
                    onValueChange = {
                        emailState.value = it
                        if (emailError.value != null) emailError.value = null // Limpa erro ao digitar
                    },
                    label = { Text(stringResource(R.string.login_email_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = stringResource(R.string.content_desc_email_icon))
                    },
                    isError = emailError.value != null,
                    supportingText = { if (emailError.value != null) Text(emailError.value!!) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Campo de Senha
                OutlinedTextField(
                    value = passwordState.value,
                    onValueChange = {
                        passwordState.value = it
                        if (passwordError.value != null) passwordError.value = null // Limpa erro ao digitar
                    },
                    label = { Text(stringResource(R.string.login_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.content_desc_password_icon))
                    },
                    isError = passwordError.value != null,
                    supportingText = { if (passwordError.value != null) Text(passwordError.value!!) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Botão de Login com e-mail/senha
                Button(
                    onClick = {
                        emailError.value = null
                        passwordError.value = null
                        if (emailState.value.isBlank()) {
                            emailError.value = context.getString(R.string.login_email_empty_error)
                            return@Button
                        }
                        if (passwordState.value.isBlank()) {
                            passwordError.value = context.getString(R.string.login_password_empty_error)
                            return@Button
                        }
                        authViewModel.login(emailState.value, passwordState.value)
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.login_button_login))
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Botão "Esqueceu a senha?"
                TextButton(
                    onClick = {
                        if (emailState.value.isBlank()) {
                            Toast.makeText(context, R.string.login_email_for_password_reset, Toast.LENGTH_LONG).show()
                            emailError.value = context.getString(R.string.login_email_empty_error)
                            return@TextButton
                        }
                        authViewModel.resetPassword(emailState.value)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.login_password_reset_sent),
                                duration = SnackbarDuration.Long
                            )
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text(stringResource(R.string.login_forgot_password))
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                // BOTÃO DE LOGIN COM GOOGLE
                OutlinedButton(
                    onClick = {
                        // Inicia o fluxo de login do Google
                        val signInIntent = authViewModel.getGoogleSignInIntent()
                        googleSignInLauncher.launch(signInIntent)
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Você pode usar um ícone do Google aqui (ex: um drawable `ic_google.xml` no `res/drawable`)
                    // Exemplo: Icon(painterResource(id = R.drawable.ic_google), contentDescription = "Login com Google")
                    Text("Entrar com Google") // Esta string pode ser adicionada ao strings.xml
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Botão para Registrar
                OutlinedButton(
                    onClick = {
                        navController.navigate(AppDestinations.REGISTER_ROUTE)
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.login_button_register))
                }
            }
        }
    }
}